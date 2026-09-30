package com.nodiumhosting.vaultmapper.server;

import com.nodiumhosting.vaultmapper.VaultMapper;
import com.nodiumhosting.vaultmapper.proto.Vault;
import com.nodiumhosting.vaultmapper.proto.VaultCell;
import com.nodiumhosting.vaultmapper.server.VaultSaveFiles.CorruptDataException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.zip.CRC32;

import static com.nodiumhosting.vaultmapper.server.VaultSaveFiles.exists;

// owns the vault save files on disk: layout and paths, background
// snapshot flushes, recovery reads and retention sweeps - nothing about live sync traffic
//
// files per vault in <world>/vaultmapper/:
//   <id>.dat     main snapshot: int32 format version + rotation id + serialized Vault proto
//   <id>.wal     active journal (record format: see VaultJournal)
//   <id>.wal.old journal rotated aside while an async flush folds it into the snapshot
//   <id>.tmp     staging for snapshot writes (moved into place atomically)
//   <id>.wal.merge.tmp staging for merging journals or upgrading a v1 journal
//   <id>.deleted deletion generation cutoff; retained if directory sync is unsupported
//   <id>.deleted.tmp staging for atomic installation of a deletion record
// clients retain sequenced batches until their records and receipts are forced to disk.
// Once enough records pile up the snapshot worker rewrites the snapshot
// from live memory. recovery reads the snapshot, skips its committed rotation if one
// remains after a crash, then replays any newer journaled cells on top of it
final class VaultSaveStore {
    // vault snapshot format version, bump when the layout changes
    private static final int SNAPSHOT_FILE_VERSION = 3;

    private static final String SNAPSHOT_SUFFIX = ".dat";
    private static final String WAL_SUFFIX = ".wal";
    private static final String ROTATED_WAL_SUFFIX = ".wal.old";
    private static final String TMP_SUFFIX = ".tmp";
    private static final String WAL_MERGE_TMP_SUFFIX = ".wal.merge.tmp";
    private static final String DELETION_SUFFIX = ".deleted";
    private static final String DELETION_TMP_SUFFIX = ".deleted.tmp";
    private static final int DELETION_VERSION = 1;
    private static final UUID LEGACY_DELETION = new UUID(Long.MAX_VALUE, 0);
    private static final String[] RECOVERABLE_SUFFIXES = {SNAPSHOT_SUFFIX, WAL_SUFFIX, ROTATED_WAL_SUFFIX};
    private static final String[] STAGING_SUFFIXES = {TMP_SUFFIX, WAL_MERGE_TMP_SUFFIX, DELETION_TMP_SUFFIX};
    // every file a vault save can consist of (order matters for extension stripping: longest first)
    private static final String[] SAVE_SUFFIXES = {WAL_MERGE_TMP_SUFFIX, DELETION_TMP_SUFFIX, ROTATED_WAL_SUFFIX, DELETION_SUFFIX, SNAPSHOT_SUFFIX, WAL_SUFFIX, TMP_SUFFIX};

    // Slow file maintenance is ordered per vault with appends, not globally with them.
    // Whole-map snapshot writes use a separate worker and do not hold the journal lane.
    static final ExecutorService FLUSH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "VaultMapper-WAL-Flush");
        thread.setDaemon(true);
        return thread;
    });
    static final ExecutorService JOURNAL_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "VaultMapper-Journal");
        thread.setDaemon(true);
        return thread;
    });
    static final ExecutorService SNAPSHOT_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "VaultMapper-Snapshot");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean sweepInFlight = new AtomicBoolean();

    @Nullable
    static Path getVaultDir() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return null;
        }
        return vaultDir(server);
    }

    private static Path vaultDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("vaultmapper");
    }

    static Path snapshotPath(Path dir, String vaultId) {
        return dir.resolve(vaultId + SNAPSHOT_SUFFIX);
    }

    static Path walPath(Path dir, String vaultId) {
        return dir.resolve(vaultId + WAL_SUFFIX);
    }

    static Path rotatedWalPath(Path dir, String vaultId) {
        return dir.resolve(vaultId + ROTATED_WAL_SUFFIX);
    }

    static Path tmpPath(Path dir, String vaultId) {
        return dir.resolve(vaultId + TMP_SUFFIX);
    }

    static Path walMergeTmpPath(Path dir, String vaultId) {
        return dir.resolve(vaultId + WAL_MERGE_TMP_SUFFIX);
    }

    static Path deletionPath(Path dir, String vaultId) {
        return dir.resolve(vaultId + DELETION_SUFFIX);
    }

    static Path deletionTmpPath(Path dir, String vaultId) {
        return dir.resolve(vaultId + DELETION_TMP_SUFFIX);
    }

    // Server thread: enqueue the immutable cell; even serialization happens on the worker.
    static void appendCell(String vaultId, SyncVault vault, VaultCell cell) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        appendCell(vaultId, vault, cell, vaultDir(server));
    }

    static CompletableFuture<Void> appendCell(String vaultId, SyncVault vault, VaultCell cell,
                                             Path dir) {
        vault.addPendingRecord();
        return VaultIoQueue.submit(walPath(dir, vaultId), JOURNAL_EXECUTOR, () -> {
            if (!vault.recoveryComplete) {
                throw new IllegalStateException("Cannot append before vault recovery succeeds");
            }
            vault.walFile = walPath(dir, vaultId);
            VaultJournal.appendCell(vaultId, vault, cell);
            return null;
        });
    }

    static CompletableFuture<Void> appendUpdate(String vaultId, SyncVault vault, UUID source, long sequence,
                                                List<VaultCell> cells, Path dir) {
        vault.addPendingRecord();
        return VaultIoQueue.submit(walPath(dir, vaultId), JOURNAL_EXECUTOR, () -> {
            vault.walFile = walPath(dir, vaultId);
            // A retry of a failed write must not journal stale values over later
            // accepted edits from other players. The receipt still identifies the
            // original batch, while the cells reflect its latest authoritative state.
            List<VaultCell> latest = cells.stream().map(cell -> vault.cells.get(SyncVault.cellKey(cell))).toList();
            VaultJournal.appendUpdate(vaultId, vault, source, sequence, latest);
            return null;
        });
    }

    // closes the vault's journal and deletes its save files (in that order - an open
    // handle would keep writing into the deleted path and block deletion on Windows)
    static void deleteSave(String vaultId, SyncVault vault) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        deleteSave(vaultId, vault, vaultDir(server));
    }

    static CompletableFuture<Void> deleteSave(String vaultId, SyncVault vault, Path dir) {
        return VaultIoQueue.submit(walPath(dir, vaultId), FLUSH_EXECUTOR, () -> {
            VaultJournal.closeChannel(vaultId, vault);
            try {
                deleteVaultFiles(dir, vaultId);
                vault.walRepairOffset = -1;
            } catch (IOException e) {
                VaultMapper.LOGGER.warn("Failed to delete vault sync save {}: {}", vaultId, e.toString());
                throw new java.io.UncheckedIOException(e);
            }
            return null;
        });
    }

    // makes sure no journal handle survives e.g. a server stop
    static void closeAllJournals(Map<String, SyncVault> vaults) {
        vaults.forEach(VaultJournal::closeChannel);
    }

    // Server thread marks the memory boundary in O(1), then queues the save after all
    // earlier appends. Only rotation/repair hold the file lane; copying/writing the
    // full snapshot runs separately while later appends continue in the new journal.
    static void flushAsync(String vaultId, SyncVault vault) {
        // resolve the world and server NOW, while still on the server thread: if this server
        // stops and another world starts in the same JVM before the task runs, the snapshot
        // must land in the world it belongs to
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            vault.flushInFlight.set(false);
            return;
        }
        flushAsync(vaultId, vault, vaultDir(server));
    }

    static void flushAsync(String vaultId, SyncVault vault, Path dir) {
        CompletableFuture<Void> finished = VaultIoQueue.reserve();
        if (finished.isDone()) {
            vault.flushInFlight.set(false);
            markFlushFailed(vault);
            return;
        }
        final SyncVault.CellSnapshot cellsAtRotation;
        final int pendingAtBoundary = vault.walPending;
        try {
            cellsAtRotation = vault.beginCellSnapshot();
        } catch (Exception e) {
            vault.flushInFlight.set(false);
            markFlushFailed(vault);
            VaultMapper.LOGGER.warn("Failed to start vault sync snapshot {}: {}", vaultId, e.toString());
            finished.completeExceptionally(e);
            return;
        }

        VaultIoQueue.submit(walPath(dir, vaultId), FLUSH_EXECUTOR, () -> {
            Files.createDirectories(dir);
            VaultJournal.repairPendingTail(walPath(dir, vaultId), vault);
            VaultJournal.closeChannel(vaultId, vault);
            reabsorbStrandedWal(vaultId, dir, vault);
            Path wal = walPath(dir, vaultId);
            UUID checkpoint = vault.committedJournalId;
            if (exists(wal)) {
                checkpoint = ensureWalHasRotationId(dir, vaultId, vault);
                Files.move(wal, rotatedWalPath(dir, vaultId), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                forceDirectory(dir);
            }
            UUID deletedThrough = readDeletionCheckpoint(dir, vaultId);
            if (deletedThrough != null && (checkpoint == null
                    || checkpoint.getMostSignificantBits() <= deletedThrough.getMostSignificantBits())) {
                checkpoint = vault.nextJournalId();
            }
            return new Rotation(checkpoint, Map.copyOf(vault.durableReceipts));
        }).thenCompose(rotation -> VaultIoQueue.independent(SNAPSHOT_EXECUTOR, () -> {
            try (cellsAtRotation) {
                Vault protoVault = Vault.newBuilder().addAllCells(cellsAtRotation.copyCells()).build();
                writeSnapshot(dir, vaultId, protoVault, rotation.id(), rotation.receipts());
                vault.committedJournalId = rotation.id();
                Files.deleteIfExists(rotatedWalPath(dir, vaultId));
                vault.completeFlush(pendingAtBoundary);
                vault.flushFailures = 0;
                vault.nextFlushAllowedMillis = 0;
                VaultMapper.LOGGER.debug("Flushed vault sync {} to disk ({} cells)", vaultId, protoVault.getCellsCount());
                return null;
            }
        })).whenComplete((value, failure) -> {
            cellsAtRotation.close();
            if (failure != null) {
                markFlushFailed(vault);
                VaultMapper.LOGGER.warn("Failed to flush vault sync {}: {}", vaultId, failure.toString());
            }
            vault.flushInFlight.set(false);
            finished.complete(null);
        });
    }

    private record Rotation(UUID id, Map<UUID, Long> receipts) {
    }

    static void writeSnapshot(Path dir, String vaultId, Vault protoVault, @Nullable UUID rotatedId) throws IOException {
        writeSnapshot(dir, vaultId, protoVault, rotatedId, Map.of());
    }

    static void writeSnapshot(Path dir, String vaultId, Vault protoVault, @Nullable UUID rotatedId,
                              Map<UUID, Long> receipts) throws IOException {
        Path tmp = tmpPath(dir, vaultId);
        byte[] vaultBytes = protoVault.toByteArray();
        ByteArrayOutputStream header = new ByteArrayOutputStream(25);
        DataOutputStream encoded = new DataOutputStream(header);
        encoded.writeInt(SNAPSHOT_FILE_VERSION);
        encoded.writeBoolean(rotatedId != null);
        if (rotatedId != null) {
            encoded.writeLong(rotatedId.getMostSignificantBits());
            encoded.writeLong(rotatedId.getLeastSignificantBits());
        }
        encoded.writeInt(vaultBytes.length);
        encoded.writeInt(receipts.size());
        for (Map.Entry<UUID, Long> receipt : receipts.entrySet()) {
            encoded.writeLong(receipt.getKey().getMostSignificantBits());
            encoded.writeLong(receipt.getKey().getLeastSignificantBits());
            encoded.writeLong(receipt.getValue());
        }
        CRC32 crc = new CRC32();
        byte[] headerBytes = header.toByteArray();
        crc.update(headerBytes); // includes the rotation metadata, not just cells
        crc.update(vaultBytes);
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            DataOutputStream out = new DataOutputStream(Channels.newOutputStream(channel));
            out.write(headerBytes);
            out.write(vaultBytes);
            out.writeInt((int) crc.getValue());
            out.flush();
            channel.force(true);
        }
        Files.move(tmp, snapshotPath(dir, vaultId), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        forceDirectory(dir);
    }

    // spaces repeated flush attempts exponentially apart (2, 4, 8 ... capped at 60 minutes)
    // so a persistently failing disk doesn't spam the log every housekeeping cycle
    private static void markFlushFailed(SyncVault vault) {
        vault.flushFailures++;
        long backoffMinutes = Math.min(60, 1L << Math.min(vault.flushFailures, 6));
        vault.nextFlushAllowedMillis = System.currentTimeMillis() + backoffMinutes * 60_000L;
    }

    // Includes chained per-vault operations and both phases of a snapshot. Waiting
    // only for a barrier on one executor would miss work on the other executors.
    static void awaitFlushDrain() {
        VaultIoQueue.awaitAll();
    }

    static void flushAllAtShutdown(Map<String, SyncVault> vaults, BiConsumer<String, SyncVault> startFlush) {
        flushAllAtShutdown(vaults, startFlush, VaultSaveStore::deleteSave);
    }

    static void flushAllAtShutdown(Map<String, SyncVault> vaults, BiConsumer<String, SyncVault> startFlush,
                                   BiConsumer<String, SyncVault> startDelete) {
        // An in-flight snapshot excludes cells changed after its boundary. Wait for
        // it first, then save the latest memory state even if those WAL appends failed.
        awaitFlushDrain();
        VaultIoQueue.independent(JOURNAL_EXECUTOR, () -> {
            closeAllJournals(vaults);
            return null;
        });
        awaitFlushDrain();
        vaults.forEach((vaultId, vault) -> {
            if (vault.retiring) {
                // A queue-full retirement may never have installed its deletion
                // cutoff. Retry now, before clearing the only record of its expiry.
                startDelete.accept(vaultId, vault);
            } else if (vault.recoveryComplete && vault.flushInFlight.compareAndSet(false, true)) {
                startFlush.accept(vaultId, vault);
            }
            // Shutdown is already waiting for disk work. Drain each final save/
            // deletion so a large number of vaults cannot refill the bounded queues.
            awaitFlushDrain();
        });
    }

    // folds an uncommitted rotated journal (left by a failed flush) into the live one
    // in chronological order: old records first, then newer live records. replace the live
    // journal atomically before deleting the strand, so a crash at any point leaves both
    // journals recoverable (duplicate old records are harmless on replay).
    static void reabsorbStrandedWal(String vaultId, Path dir) throws IOException {
        SyncVault vault = new SyncVault();
        vault.committedJournalId = readSnapshotRotationId(snapshotPath(dir, vaultId));
        vault.observeJournalId(vault.committedJournalId);
        reabsorbStrandedWal(vaultId, dir, vault);
    }

    private static void reabsorbStrandedWal(String vaultId, Path dir, SyncVault vault) throws IOException {
        Path stranded = rotatedWalPath(dir, vaultId);
        UUID committedId = vault.committedJournalId;
        // On filesystems that cannot force directory renames, a committed rotation
        // can reappear at the original .wal path after a crash. Do not rotate it
        // again and give it a new checkpoint identity.
        discardCommittedActiveWal(dir, vaultId, committedId);
        if (!exists(stranded)) {
            return;
        }
        // The snapshot already absorbed this exact rotation. Replaying or merging it
        // could undo a cell update that reached the snapshot after its WAL append failed.
        if (isWalIncluded(stranded, committedId)) {
            Files.deleteIfExists(stranded);
            return;
        }
        byte[] oldBytes = Files.readAllBytes(stranded);
        int oldHeaderSize = oldBytes.length == 0 ? 0 : VaultJournal.headerLength(oldBytes);
        int oldEnd = oldBytes.length == 0 ? 0 : VaultJournal.scanParsableEnd(oldBytes);
        if (oldEnd < 0) {
            // unreadable strand (also torn/foreign header) - nothing in it can be salvaged
            VaultMapper.LOGGER.warn("Discarding unreadable rotated vault sync journal of {}", vaultId);
        } else if (oldEnd > oldHeaderSize) {
            Path wal = walPath(dir, vaultId);
            byte[] liveBytes = exists(wal) ? Files.readAllBytes(wal) : new byte[0];
            int liveHeaderSize = liveBytes.length == 0 ? 0 : VaultJournal.headerLength(liveBytes);
            int liveEnd = liveBytes.length == 0 ? 0 : VaultJournal.scanParsableEnd(liveBytes);
            if (liveEnd < 0) {
                throw new IOException("Cannot merge rotated vault sync journal into unreadable live journal of " + vaultId);
            }
            if (oldEnd < oldBytes.length || liveEnd < liveBytes.length) {
                VaultMapper.LOGGER.warn("Trimming unreadable records while merging vault sync journals of {}", vaultId);
            }

            Path staging = walMergeTmpPath(dir, vaultId);
            try (FileChannel channel = FileChannel.open(staging, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                vault.observeJournalId(VaultJournal.rotationId(oldBytes));
                vault.observeJournalId(VaultJournal.rotationId(liveBytes));
                writeFully(channel, VaultJournal.header(vault.nextJournalId()));
                writeFully(channel, oldBytes, oldHeaderSize, oldEnd - oldHeaderSize);
                writeFully(channel, liveBytes, liveHeaderSize, liveEnd - liveHeaderSize);
                channel.force(true);
            }
            Files.move(staging, wal, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            forceDirectory(dir);
        }
        Files.deleteIfExists(stranded);
    }

    // Convert a v1 WAL only when it is about to be rotated. The old WAL remains intact
    // until the replacement with an embedded id has been forced and atomically moved.
    static UUID ensureWalHasRotationId(Path dir, String vaultId) throws IOException {
        SyncVault vault = new SyncVault();
        vault.observeJournalId(readSnapshotRotationId(snapshotPath(dir, vaultId)));
        return ensureWalHasRotationId(dir, vaultId, vault);
    }

    private static UUID ensureWalHasRotationId(Path dir, String vaultId, SyncVault vault) throws IOException {
        Path wal = walPath(dir, vaultId);
        byte[] bytes;
        int end;
        int headerSize;
        if (Files.size(wal) == 0) {
            // A failed first append may have left an empty journal; the snapshot
            // can still preserve the cells already accepted in memory.
            bytes = new byte[0];
            end = 0;
            headerSize = 0;
        } else {
            VaultJournal.Header existing = VaultJournal.readHeader(wal);
            if (existing.id() != null && existing.version() == VaultJournal.VERSION) {
                vault.observeJournalId(existing.id());
                return existing.id();
            }
            bytes = Files.readAllBytes(wal);
            end = VaultJournal.scanParsableEnd(bytes);
            headerSize = VaultJournal.headerLength(bytes);
            if (end < 0) {
                throw new IOException("Cannot upgrade unreadable vault sync journal of " + vaultId);
            }
        }
        UUID existing = VaultJournal.rotationId(bytes);
        UUID id = existing != null ? existing : vault.nextJournalId();
        vault.observeJournalId(id);
        Path staging = walMergeTmpPath(dir, vaultId);
        try (FileChannel channel = FileChannel.open(staging, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writeFully(channel, VaultJournal.header(id));
            writeFully(channel, bytes, headerSize, end - headerSize);
            channel.force(true);
        }
        Files.move(staging, wal, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        forceDirectory(dir);
        return id;
    }

    @Nullable
    private static UUID readRotationId(DataInputStream in) throws IOException {
        if (!in.readBoolean()) {
            return null;
        }
        return new UUID(in.readLong(), in.readLong());
    }

    private record Snapshot(Vault cells, @Nullable UUID rotatedId, Map<UUID, Long> receipts) {
    }

    private static Snapshot readSnapshot(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int version = in.readInt();
            if (version == 1) {
                return new Snapshot(Vault.parseFrom(in), null, Map.of());
            }
            if (version != 2 && version != SNAPSHOT_FILE_VERSION) {
                throw new IOException("Unknown vault sync snapshot format version " + version);
            }
            UUID id = readRotationId(in);
            int length = in.readInt();
            Map<UUID, Long> receipts = new java.util.HashMap<>();
            if (version >= 3) {
                int count = in.readInt();
                if (count < 0 || count > 4096 || count > in.available() / 24) {
                    throw new IOException("Invalid vault receipt count");
                }
                for (int i = 0; i < count; i++) {
                    UUID source = new UUID(in.readLong(), in.readLong());
                    long sequence = in.readLong();
                    if (sequence <= 0 || receipts.put(source, sequence) != null) {
                        throw new IOException("Invalid vault receipt");
                    }
                }
            }
            if (length < 0 || length != in.available() - Integer.BYTES) {
                throw new IOException("Incomplete vault sync snapshot " + file.getFileName());
            }
            byte[] payload = new byte[length];
            in.readFully(payload);
            int checksum = in.readInt();
            CRC32 crc = new CRC32();
            crc.update(bytes, 0, bytes.length - Integer.BYTES);
            if ((int) crc.getValue() != checksum) {
                throw new IOException("Corrupt vault sync snapshot " + file.getFileName());
            }
            return new Snapshot(Vault.parseFrom(payload), id, receipts);
        } catch (IOException e) {
            // All I/O here is on an in-memory buffer; disk reads happened above.
            throw new CorruptDataException("Corrupt vault sync snapshot " + file.getFileName(), e);
        }
    }

    @Nullable
    private static UUID readSnapshotRotationId(Path snapshot) throws IOException {
        if (!exists(snapshot)) {
            return null;
        }
        try {
            return readSnapshot(snapshot).rotatedId();
        } catch (CorruptDataException e) {
            VaultMapper.LOGGER.warn("Cannot check vault sync snapshot {} for a committed journal: {}", snapshot.getFileName(), e.toString());
            return null;
        }
    }

    private static boolean isWalIncluded(Path wal, @Nullable UUID snapshotId) throws IOException {
        if (snapshotId == null || !exists(wal)) {
            return false;
        }
        try {
            UUID walId = VaultJournal.readRotationId(wal);
            // A v1 journal has generation zero. Once a v2 snapshot has committed,
            // no v1 journal can contain a later append: writes create v2 journals.
            return walId == null || walId.getMostSignificantBits() <= snapshotId.getMostSignificantBits();
        } catch (CorruptDataException e) {
            VaultMapper.LOGGER.warn("Cannot read vault sync journal header {}: {}", wal.getFileName(), e.toString());
            return false;
        }
    }

    private static void discardCommittedActiveWal(Path dir, String vaultId, @Nullable UUID snapshotId) throws IOException {
        Path wal = walPath(dir, vaultId);
        if (isWalIncluded(wal, snapshotId)) {
            Files.delete(wal);
        }
    }

    private static void writeFully(FileChannel channel, byte[] bytes) throws IOException {
        writeFully(channel, bytes, 0, bytes.length);
    }

    private static void writeFully(FileChannel channel, byte[] bytes, int offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    // best-effort: forces the directory so a rename (like the snapshot move) is itself durable
    private static void forceDirectory(Path dir) {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (Exception ignored) {
            // some filesystems can't force directories - the file contents are still safe
        }
    }

    // recovery, returning the number of journaled cells applied: snapshot first, then
    // replay the older rotated journal only if that snapshot did not already absorb it,
    // followed by the live journal containing post-snapshot updates
    static CompletableFuture<Integer> loadAsync(String vaultId, SyncVault vault) {
        Path dir = getVaultDir();
        if (dir == null) {
            return CompletableFuture.completedFuture(0);
        }
        return loadAsync(dir, vaultId, vault);
    }

    static CompletableFuture<Integer> loadAsync(Path dir, String vaultId, SyncVault vault) {
        return loadAsync(dir, vaultId, vault, VaultSaveStore::syncDeletionDirectory);
    }

    static CompletableFuture<Integer> loadAsync(Path dir, String vaultId, SyncVault vault, DirectorySync directorySync) {
        vault.recoveryComplete = false;
        return VaultIoQueue.submit(walPath(dir, vaultId), FLUSH_EXECUTOR, () -> {
            try {
                finishInterruptedDeletion(dir, vaultId, directorySync);
                vault.resetRecoveredCells();
                int replayed = loadVault(dir, vaultId, vault);
                vault.recoveryComplete = false;
                Path wal = walPath(dir, vaultId);
                if (exists(wal) && Files.size(wal) > 0) ensureWalHasRotationId(dir, vaultId, vault);
                if (replayed > 0) {
                    vault.walPending = Math.max(100, replayed); // fold recovered journals in on housekeeping
                }
                vault.recoveryComplete = true;
                return replayed;
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    static int loadVault(Path dir, String vaultId, SyncVault vault) throws IOException {
        vault.recoveryComplete = false;
        try {
            UUID deletedThrough = readDeletionCheckpoint(dir, vaultId);
            // Old one-byte markers predate cutoffs and unconditionally retire all
            // survivors. Async recovery upgrades them before starting a fresh save.
            if (LEGACY_DELETION.equals(deletedThrough)) {
                return 0;
            }
            Path snapshot = snapshotPath(dir, vaultId);
            UUID includedRotation = deletedThrough;
            vault.committedJournalId = deletedThrough;
            vault.observeJournalId(deletedThrough);
            if (exists(snapshot)) {
                try {
                    Snapshot saved = readSnapshot(snapshot);
                    if (!generationIncluded(saved.rotatedId(), deletedThrough)) {
                        saved.cells().getCellsList().forEach(vault::putCell);
                        includedRotation = saved.rotatedId();
                        vault.committedJournalId = includedRotation;
                        vault.observeJournalId(includedRotation);
                        vault.durableReceipts.putAll(saved.receipts());
                    }
                } catch (CorruptDataException e) {
                    VaultMapper.LOGGER.warn("Failed to read vault sync snapshot {}: {}", vaultId, e.toString());
                    // A damaged snapshot must not prevent recovery from its journals.
                }
            }

            int replayed = 0;
            observeJournalId(rotatedWalPath(dir, vaultId), vault);
            observeJournalId(walPath(dir, vaultId), vault);
            if (!isWalIncluded(rotatedWalPath(dir, vaultId), includedRotation)) {
                replayed = VaultJournal.replay(rotatedWalPath(dir, vaultId), vaultId, vault);
            }
            if (isWalIncluded(walPath(dir, vaultId), includedRotation)) {
                // Drop the absorbed WAL before this live vault accepts any new appends.
                // Otherwise later updates could go into the skipped journal.
                discardCommittedActiveWal(dir, vaultId, includedRotation);
            } else {
                replayed += VaultJournal.replay(walPath(dir, vaultId), vaultId, vault);
            }

            VaultMapper.LOGGER.info("Loaded vault sync {} from disk ({} cells, {} journaled)", vaultId, vault.cells.size(), replayed);
            vault.acceptedSequences.putAll(vault.durableReceipts);
            vault.recoveryComplete = true;
            return replayed;
        } catch (IOException e) {
            VaultMapper.LOGGER.warn("Failed to load vault sync {}: {}", vaultId, e.toString());
            throw e;
        }
    }

    private static void observeJournalId(Path file, SyncVault vault) throws IOException {
        if (exists(file)) {
            try {
                vault.observeJournalId(VaultJournal.readRotationId(file));
            } catch (CorruptDataException e) {
                VaultMapper.LOGGER.warn("Cannot read vault sync journal header {}: {}", file.getFileName(), e.toString());
            }
        }
    }

    static void deleteVaultFiles(Path dir, String vaultId) throws IOException {
        deleteVaultFiles(dir, vaultId, VaultSaveStore::syncDeletionDirectory);
    }

    @FunctionalInterface
    interface DirectorySync {
        // false means directory syncing is unsupported, NOT that a supported sync failed
        boolean force(Path dir) throws IOException;
    }

    static void deleteVaultFiles(Path dir, String vaultId, DirectorySync directorySync) throws IOException {
        if (!exists(dir)) {
            return;
        }
        directorySync.force(dir);
        UUID prior = readDeletionCheckpoint(dir, vaultId);
        long cutoff = latestGenerationOnDisk(dir, vaultId);
        if (prior != null && !LEGACY_DELETION.equals(prior)) {
            cutoff = Math.max(cutoff, prior.getMostSignificantBits());
        }
        writeDeletionCheckpoint(dir, vaultId, cutoff);
        directorySync.force(dir);
        finishInterruptedDeletion(dir, vaultId, directorySync);
    }

    private static void finishInterruptedDeletion(Path dir, String vaultId, DirectorySync directorySync) throws IOException {
        Path marker = deletionPath(dir, vaultId);
        UUID deletedThrough = readDeletionCheckpoint(dir, vaultId);
        if (deletedThrough == null) {
            return;
        }
        if (LEGACY_DELETION.equals(deletedThrough)) {
            // Imported/old markers still mean everything currently on disk is deleted.
            writeDeletionCheckpoint(dir, vaultId, latestGenerationOnDisk(dir, vaultId));
            deletedThrough = readDeletionCheckpoint(dir, vaultId);
        }
        boolean directoryIsDurable = directorySync.force(dir);
        for (String suffix : SAVE_SUFFIXES) {
            if (suffix.equals(DELETION_SUFFIX)) {
                continue;
            }
            Path component = dir.resolve(vaultId + suffix);
            if (exists(component) && (suffix.endsWith(TMP_SUFFIX)
                    || generationOnDisk(component, suffix) <= deletedThrough.getMostSignificantBits())) {
                Files.delete(component);
            }
        }
        // Windows' JDK cannot open a directory channel. Keep the small, forced
        // cutoff instead of either failing cleanup or retiring its safety record.
        // Future saves use larger generations and are not hidden by this marker.
        if (directorySync.force(dir) && directoryIsDurable) {
            Files.delete(marker);
            directorySync.force(dir);
        }
    }

    private static boolean syncDeletionDirectory(Path dir) throws IOException {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            return false;
        }
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
            return true;
        } catch (UnsupportedOperationException e) {
            return false;
        }
    }

    private static boolean generationIncluded(@Nullable UUID id, @Nullable UUID cutoff) {
        return cutoff != null && (id == null || id.getMostSignificantBits() <= cutoff.getMostSignificantBits());
    }

    private static long latestGenerationOnDisk(Path dir, String vaultId) throws IOException {
        long generation = 0;
        for (String suffix : RECOVERABLE_SUFFIXES) {
            Path file = dir.resolve(vaultId + suffix);
            if (exists(file)) {
                generation = Math.max(generation, generationOnDisk(file, suffix));
            }
        }
        return generation;
    }

    private static long generationOnDisk(Path file, String suffix) throws IOException {
        // A foreign directory is not a journal generation; attempting to remove
        // it below will still fail safely with the deletion record in place.
        if (!Files.readAttributes(file, BasicFileAttributes.class).isRegularFile()) {
            return 0;
        }
        UUID id;
        try {
            id = suffix.equals(SNAPSHOT_SUFFIX) ? readSnapshot(file).rotatedId() : VaultJournal.readRotationId(file);
        } catch (CorruptDataException e) {
            // A corrupt component belongs to the save being retired as well.
            id = null;
        }
        return id == null ? 0 : id.getMostSignificantBits();
    }

    @Nullable
    private static UUID readDeletionCheckpoint(Path dir, String vaultId) throws IOException {
        Path marker = deletionPath(dir, vaultId);
        if (!exists(marker)) {
            return null;
        }
        byte[] bytes = Files.readAllBytes(marker);
        if (bytes.length == 1 && bytes[0] == 1) {
            return LEGACY_DELETION;
        }
        if (bytes.length != 16) {
            throw new IOException("Incomplete vault deletion record " + marker);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int version = buffer.getInt();
        long generation = buffer.getLong();
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, 12);
        if (version != DELETION_VERSION || generation < 0 || buffer.getInt() != (int) crc.getValue()) {
            throw new IOException("Corrupt vault deletion record " + marker);
        }
        return new UUID(generation, 0);
    }

    private static void writeDeletionCheckpoint(Path dir, String vaultId, long generation) throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(16).putInt(DELETION_VERSION).putLong(generation);
        CRC32 crc = new CRC32();
        crc.update(bytes.array(), 0, 12);
        bytes.putInt((int) crc.getValue());
        Path staging = deletionTmpPath(dir, vaultId);
        // Never create/truncate the live marker before the complete replacement
        // has been forced. Interruption leaves either the old cutoff or no cutoff;
        // no component deletions have started yet in either case.
        try (FileChannel marker = FileChannel.open(staging,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writeFully(marker, bytes.array());
            marker.force(true);
        }
        Files.move(staging, deletionPath(dir, vaultId), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // newest modification time of recoverable files only; abandoned staging files must
    // not extend the life of a snapshot or journal that is otherwise expired
    private static long lastSaveModifiedMillis(Path dir, String vaultId) throws IOException {
        long latest = -1;
        for (String suffix : RECOVERABLE_SUFFIXES) {
            Path file = dir.resolve(vaultId + suffix);
            if (exists(file)) {
                latest = Math.max(latest, Files.getLastModifiedTime(file).toMillis());
            }
        }
        return latest;
    }

    private static void pruneOrphanStagingFiles(Path dir, String vaultId, long now, long retentionMs) throws IOException {
        for (String suffix : STAGING_SUFFIXES) {
            Path file = dir.resolve(vaultId + suffix);
            if (exists(file) && now - Files.getLastModifiedTime(file).toMillis() > retentionMs) {
                VaultMapper.LOGGER.debug("Pruning abandoned vault sync staging file {}", file.getFileName());
                Files.deleteIfExists(file);
            }
        }
    }

    // maps a save file name back to its vault id, or null if it isn't one of ours
    @Nullable
    private static String stripSaveSuffix(String name) {
        for (String suffix : SAVE_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return null;
    }

    // Server thread: schedule startup/periodic cleanup; directory scanning is off-thread too.
    static void sweepAsync(long now, long retentionMs, Set<String> liveVaultIds) {
        Path dir = getVaultDir();
        if (dir != null) {
            sweepAsync(dir, now, retentionMs, liveVaultIds);
        }
    }

    static CompletableFuture<Void> sweepAsync(Path dir, long now, long retentionMs, Set<String> liveVaultIds) {
        if (!sweepInFlight.compareAndSet(false, true)) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> result = VaultIoQueue.independent(FLUSH_EXECUTOR, () -> {
            try {
                if (exists(dir)) {
                    sweepVaultFilesOnDisk(dir, now, retentionMs, liveVaultIds);
                }
            } catch (Exception e) {
                VaultMapper.LOGGER.warn("Failed to sweep vault sync saves: {}", e.toString());
            }
            return null;
        });
        result.whenComplete((value, error) -> sweepInFlight.set(false));
        return result;
    }

    // decide retention for a vault as a whole, not one file at a time: an old snapshot
    // may still be required even when a newer journal is the only recently written file
    static void sweepVaultFilesOnDisk(Path dir, long now, long retentionMs, Set<String> liveVaultIds) throws IOException {
        Set<String> vaultIds = new HashSet<>();
        try (var files = Files.list(dir)) {
            files.map(file -> stripSaveSuffix(file.getFileName().toString()))
                    .filter(Objects::nonNull)
                    .forEach(vaultIds::add);
        }
        for (String vaultId : vaultIds) {
            // vaults loaded in memory are governed by the in-memory sweep instead
            if (liveVaultIds.contains(vaultId)) {
                continue;
            }
            try {
                if (exists(deletionPath(dir, vaultId))) {
                    finishInterruptedDeletion(dir, vaultId, VaultSaveStore::syncDeletionDirectory);
                }
                long lastModified = lastSaveModifiedMillis(dir, vaultId);
                if (lastModified >= 0 && now - lastModified > retentionMs) {
                    VaultMapper.LOGGER.debug("Pruning vault sync save {}", vaultId);
                    deleteVaultFiles(dir, vaultId);
                } else {
                    pruneOrphanStagingFiles(dir, vaultId, now, retentionMs);
                }
            } catch (Exception e) {
                VaultMapper.LOGGER.warn("Failed to prune vault sync save {}: {}", vaultId, e.toString());
            }
        }
    }
}
