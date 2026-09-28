package com.nodiumhosting.vaultmapper.server;

import com.nodiumhosting.vaultmapper.proto.Message;
import com.nodiumhosting.vaultmapper.proto.VaultCell;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

// shared state of one live vault sync session: who is in it, the merged map, the latest
// player positions and queued movement updates, and the disk-side bookkeeping for its
// journal + snapshot flushes.
// everything is either a concurrent structure or is mutated strictly on the server thread
class SyncVault {
    final Set<UUID> players = ConcurrentHashMap.newKeySet();
    final Map<String, VaultCell> cells = new ConcurrentHashMap<>();
    final Map<UUID, Message> latestMoves = new ConcurrentHashMap<>();
    final Map<UUID, Message> pendingMoves = new ConcurrentHashMap<>();
    volatile long emptySince = 0;
    volatile long lastMoveRelayNanos = 0;
    // journal records written since the last flush rotation. mutated on the server
    // thread ONLY (packet handlers, join/load, housekeeping tick) - the plain ++
    // would silently lose counts if a writer ever moved off the server thread
    volatile int walPending = 0;
    // set while a flush owns the .wal.old slot
    final AtomicBoolean flushInFlight = new AtomicBoolean(false);
    // the file walChannel is (or would be) writing to - set before every append, so the
    // channel and its backing path can't silently disagree; cleared when the channel closes
    volatile Path walFile = null;
    // open journal channel for appends - server thread only, closed before the
    // journal is renamed or deleted
    volatile FileChannel walChannel = null;
    // consecutive flush failures, and when a flush is next allowed - repeated attempts
    // are spaced exponentially apart (see VaultSaveStore.markFlushFailed)
    volatile int flushFailures = 0;
    volatile long nextFlushAllowedMillis = 0;

    // cells are keyed in the map (and compared client/server) by "x,z"
    static String cellKey(VaultCell cell) {
        return cell.getX() + "," + cell.getZ();
    }
}
