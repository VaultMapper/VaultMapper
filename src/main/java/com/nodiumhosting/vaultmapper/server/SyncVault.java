package com.nodiumhosting.vaultmapper.server;

import com.nodiumhosting.vaultmapper.proto.Message;
import com.nodiumhosting.vaultmapper.proto.VaultCell;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;

// shared state of one live vault sync session: who is in it, the merged map, the latest
// player positions and queued movement updates, and the disk-side bookkeeping for its
// journal + snapshot flushes.
// everything is either a concurrent structure or is mutated strictly on the server thread
class SyncVault {
    final Set<UUID> players = ConcurrentHashMap.newKeySet();
    private final Map<String, VaultCell> liveCells = new ConcurrentHashMap<>();
    // All writes go through putCell so an off-thread snapshot can recover the
    // value each cell had at its boundary without blocking the server on a copy.
    final Map<String, VaultCell> cells = Collections.unmodifiableMap(liveCells);
    private final Object cellSnapshotLock = new Object();
    private CellSnapshot activeCellSnapshot;
    final Map<UUID, Message> latestMoves = new ConcurrentHashMap<>();
    final Map<UUID, Message> pendingMoves = new ConcurrentHashMap<>();
    final Set<UUID> awaitingSnapshot = ConcurrentHashMap.newKeySet();
    final Map<UUID, VaultSnapshotTransfer> initialSyncs = new ConcurrentHashMap<>();
    boolean loaded = true; // server thread; false until async recovery completes
    boolean loadInFlight = false; // server thread
    boolean retiring = false; // block joins/flushes until expired files are removed
    boolean deleteInFlight = false; // server thread; rejected cleanup is retried
    long nextLoadAllowedMillis = 0; // server thread; retry a failed recovery
    volatile boolean recoveryComplete = true; // worker; also checked by shutdown
    volatile long emptySince = 0;
    volatile long lastMoveRelayNanos = 0;
    // Journal attempts submitted since the saved memory boundary, including rejects.
    // Count on submission, not completion, so rotation cannot absorb later failures.
    volatile int walPending = 0;
    final Map<UUID, Long> durableReceipts = new ConcurrentHashMap<>();
    final Map<UUID, Long> acceptedSequences = new ConcurrentHashMap<>();
    final Map<UUID, CompletableFuture<Void>> pendingWrites = new ConcurrentHashMap<>();
    // set while a flush owns the .wal.old slot
    final AtomicBoolean flushInFlight = new AtomicBoolean(false);
    // the file walChannel is (or would be) writing to - set before every append, so the
    // channel and its backing path can't silently disagree; cleared when the channel closes
    volatile Path walFile = null;
    // open journal channel for appends - disk worker only, closed before the
    // journal is renamed or deleted
    volatile FileChannel walChannel = null;
    long walRepairOffset = -1; // disk lane; no append may bypass an unconfirmed rollback
    // journal generations are ordered; a snapshot checkpoint absorbs every generation
    // up to its own, including journals resurrected by an undurable directory rename
    long maxJournalGeneration = 0; // disk worker only
    volatile UUID committedJournalId = null;

    UUID nextJournalId() {
        return new UUID(++maxJournalGeneration, UUID.randomUUID().getLeastSignificantBits());
    }

    void observeJournalId(UUID id) {
        if (id != null) {
            maxJournalGeneration = Math.max(maxJournalGeneration, id.getMostSignificantBits());
        }
    }
    // consecutive flush failures, and when a flush is next allowed - repeated attempts
    // are spaced exponentially apart (see VaultSaveStore.markFlushFailed)
    volatile int flushFailures = 0;
    volatile long nextFlushAllowedMillis = 0;

    synchronized void addPendingRecord() {
        walPending++;
    }

    synchronized void completeFlush(int absorbedRecords) {
        walPending = Math.max(0, walPending - absorbedRecords);
    }

    // Disk worker, while clients are still waiting for recovery. A retry must not
    // retain cells from an earlier partially successful attempt.
    void resetRecoveredCells() {
        synchronized (cellSnapshotLock) {
            if (activeCellSnapshot != null) {
                throw new IllegalStateException("Cannot recover while a snapshot is active");
            }
            liveCells.clear();
            committedJournalId = null;
            durableReceipts.clear();
            acceptedSequences.clear();
            walRepairOffset = -1;
        }
    }

    boolean acceptsSource(UUID source) {
        return acceptedSequences.containsKey(source) || durableReceipts.containsKey(source)
                || acceptedSequences.size() < 4096;
    }

    boolean needsStreamReset(UUID source, long sequence) {
        return sequence > 1 && !durableReceipts.containsKey(source)
                && !acceptedSequences.containsKey(source) && !pendingWrites.containsKey(source);
    }

    void putCell(VaultCell cell) {
        String key = cellKey(cell);
        synchronized (cellSnapshotLock) {
            if (activeCellSnapshot != null && !activeCellSnapshot.beforeChanges.containsKey(key)) {
                // null records a cell that did not exist at the snapshot boundary.
                activeCellSnapshot.beforeChanges.put(key, liveCells.get(key));
            }
            liveCells.put(key, cell);
        }
    }

    // Server thread: constant-time boundary, not a whole-map copy. Cell protos
    // are immutable, so keeping the old reference is sufficient for changed cells.
    CellSnapshot beginCellSnapshot() {
        synchronized (cellSnapshotLock) {
            if (activeCellSnapshot != null) {
                throw new IllegalStateException("A vault cell snapshot is already being captured");
            }
            activeCellSnapshot = new CellSnapshot();
            return activeCellSnapshot;
        }
    }

    final class CellSnapshot implements AutoCloseable {
        // A balanced tree avoids rehashing a large change log on a single server
        // tick when the worker is queued. Recording a change takes O(log n) work.
        private final Map<String, VaultCell> beforeChanges = new TreeMap<>();

        // Worker thread: copy the live map while writes continue. A cell changed
        // during this traversal has its original value recorded by putCell.
        List<VaultCell> copyCells() {
            try {
                Map<String, VaultCell> captured = new HashMap<>(liveCells);
                synchronized (cellSnapshotLock) {
                    if (activeCellSnapshot != this) {
                        throw new IllegalStateException("Vault cell snapshot is no longer active");
                    }
                    // Hand the change log to the worker in O(1). No later write
                    // can alter it; the overlay below needs no server-thread lock.
                    activeCellSnapshot = null;
                }
                beforeChanges.forEach((key, original) -> {
                    if (original == null) {
                        captured.remove(key);
                    } else {
                        captured.put(key, original);
                    }
                });
                return List.copyOf(captured.values());
            } finally {
                close();
            }
        }

        @Override
        public void close() {
            synchronized (cellSnapshotLock) {
                if (activeCellSnapshot == this) {
                    activeCellSnapshot = null;
                }
            }
        }
    }

    // cells are keyed in the map (and compared client/server) by "x,z"
    static String cellKey(VaultCell cell) {
        return cell.getX() + "," + cell.getZ();
    }
}
