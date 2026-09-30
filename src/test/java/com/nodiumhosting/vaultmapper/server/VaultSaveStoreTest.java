package com.nodiumhosting.vaultmapper.server;

import com.nodiumhosting.vaultmapper.proto.Vault;
import com.nodiumhosting.vaultmapper.proto.VaultCell;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VaultSaveStoreTest {
    private static final String VAULT_ID = "vault_00000000-0000-0000-0000-000000000001";

    @TempDir
    Path dir;

    @Test
    void reabsorbPreservesNewerCellAfterFailedFlush() throws IOException {
        Path old = VaultSaveStore.rotatedWalPath(dir, VAULT_ID);
        Path wal = VaultSaveStore.walPath(dir, VAULT_ID);
        byte[] oldBytes = journal(cell(1, "old"), cell(2, "only in old"));
        Files.write(old, oldBytes);
        Files.write(wal, journal(cell(1, "new")));

        VaultSaveStore.reabsorbStrandedWal(VAULT_ID, dir);

        assertFalse(Files.exists(old));
        assertFalse(Files.exists(VaultSaveStore.walMergeTmpPath(dir, VAULT_ID)));
        SyncVault recovered = new SyncVault();
        VaultJournal.replay(wal, VAULT_ID, recovered);
        assertEquals("new", recovered.cells.get("1,0").getRoomName());
        assertEquals("only in old", recovered.cells.get("2,0").getRoomName());

        // If a crash happened just after the atomic replacement but before deleting
        // .wal.old, recovery must still apply the newer value last.
        Files.write(old, oldBytes);
        SyncVault recoveredAfterCrash = new SyncVault();
        VaultJournal.replay(old, VAULT_ID, recoveredAfterCrash);
        VaultJournal.replay(wal, VAULT_ID, recoveredAfterCrash);
        assertEquals("new", recoveredAfterCrash.cells.get("1,0").getRoomName());
    }

    @Test
    void reabsorbDoesNotDiscardOldJournalWhenLiveJournalIsUnreadable() throws IOException {
        Path old = VaultSaveStore.rotatedWalPath(dir, VAULT_ID);
        Path wal = VaultSaveStore.walPath(dir, VAULT_ID);
        byte[] oldBytes = journal(cell(1, "old"));
        byte[] tornHeader = {1, 2, 3};
        Files.write(old, oldBytes);
        Files.write(wal, tornHeader);

        assertThrows(IOException.class, () -> VaultSaveStore.reabsorbStrandedWal(VAULT_ID, dir));

        assertTrue(Files.exists(old));
        assertEquals(oldBytes.length, Files.size(old));
        assertEquals(tornHeader.length, Files.size(wal));
    }

    @Test
    void committedSnapshotSkipsItsRotatedWalEvenWhenNewerAppendFailed() throws IOException {
        Path old = VaultSaveStore.rotatedWalPath(dir, VAULT_ID);
        UUID rotation = id(1);
        Files.write(old, journal(rotation, cell(1, "old")));
        // The in-memory cell reached the snapshot although its journal append failed.
        VaultSaveStore.writeSnapshot(dir, VAULT_ID, Vault.newBuilder().addCells(cell(1, "new")).build(), rotation);

        SyncVault recovered = new SyncVault();
        VaultSaveStore.loadVault(dir, VAULT_ID, recovered);
        assertEquals("new", recovered.cells.get("1,0").getRoomName());

        // Retrying a flush must not merge the already-absorbed old record into .wal.
        VaultSaveStore.reabsorbStrandedWal(VAULT_ID, dir);
        assertFalse(Files.exists(old));
        assertFalse(Files.exists(VaultSaveStore.walPath(dir, VAULT_ID)));
    }

    @Test
    void uncommittedRotationStillReplaysOverPreviousSnapshot() throws IOException {
        Path old = VaultSaveStore.rotatedWalPath(dir, VAULT_ID);
        UUID committed = id(1);
        VaultSaveStore.writeSnapshot(dir, VAULT_ID, Vault.newBuilder().addCells(cell(1, "snapshot")).build(), committed);
        Files.write(old, journal(id(2), cell(1, "journal")));

        SyncVault recovered = new SyncVault();
        VaultSaveStore.loadVault(dir, VAULT_ID, recovered);
        assertEquals("journal", recovered.cells.get("1,0").getRoomName());
    }

    @Test
    void newerLiveWalStillReplaysAfterCommittedSnapshot() throws IOException {
        UUID rotation = id(1);
        Files.write(VaultSaveStore.rotatedWalPath(dir, VAULT_ID), journal(rotation, cell(1, "old")));
        VaultSaveStore.writeSnapshot(dir, VAULT_ID, Vault.newBuilder().addCells(cell(1, "snapshot")).build(), rotation);
        Files.write(VaultSaveStore.walPath(dir, VAULT_ID), journal(id(2), cell(1, "new")));

        SyncVault recovered = new SyncVault();
        VaultSaveStore.loadVault(dir, VAULT_ID, recovered);
        assertEquals("new", recovered.cells.get("1,0").getRoomName());
    }

    @Test
    void offThreadSnapshotStopsAtRotationWhileNewerWalRemainsRecoverable() throws Exception {
        SyncVault vault = new SyncVault();
        vault.observeJournalId(id(1));
        vault.putCell(cell(1, "snapshot boundary"));
        SyncVault.CellSnapshot snapshot = vault.beginCellSnapshot();

        vault.putCell(cell(1, "later journaled update"));
        vault.walFile = VaultSaveStore.walPath(dir, VAULT_ID);
        assertTrue(VaultJournal.appendCell(VAULT_ID, vault, cell(1, "later journaled update")));
        VaultJournal.closeChannel(VAULT_ID, vault);
        // A later failed append must not sneak into the older snapshot as the
        // worker catches up; it belongs to a future save, not this checkpoint.
        vault.putCell(cell(1, "later unjournaled update"));

        Vault captured = CompletableFuture.supplyAsync(() -> Vault.newBuilder()
                .addAllCells(snapshot.copyCells()).build()).get(10, TimeUnit.SECONDS);
        assertEquals("snapshot boundary", captured.getCells(0).getRoomName());
        VaultSaveStore.writeSnapshot(dir, VAULT_ID, captured, id(1));

        SyncVault recovered = new SyncVault();
        VaultSaveStore.loadVault(dir, VAULT_ID, recovered);
        assertEquals("later journaled update", recovered.cells.get("1,0").getRoomName());
    }

    @Test
    void shutdownFlushPersistsFailedAppendAfterAnInFlightSnapshot() throws Exception {
        SyncVault vault = new SyncVault();
        vault.putCell(cell(1, "first snapshot"));
        vault.walFile = VaultSaveStore.walPath(dir, VAULT_ID);
        assertTrue(VaultJournal.appendCell(VAULT_ID, vault, cell(1, "first snapshot")));
        CountDownLatch workerBlocked = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        Future<?> blocker = VaultSaveStore.FLUSH_EXECUTOR.submit(() -> {
            workerBlocked.countDown();
            try {
                if (!releaseWorker.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release the save worker");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });
        try {
            assertTrue(workerBlocked.await(10, TimeUnit.SECONDS));
            vault.flushInFlight.set(true);
            VaultSaveStore.flushAsync(VAULT_ID, vault, dir);
            // Accepted in memory, but simulate its WAL append having failed.
            vault.putCell(cell(1, "latest unjournaled update"));

            AtomicBoolean finalFlushStarted = new AtomicBoolean(false);
            CountDownLatch shutdownStarted = new CountDownLatch(1);
            CompletableFuture<Void> shutdown = CompletableFuture.runAsync(() -> {
                shutdownStarted.countDown();
                VaultSaveStore.flushAllAtShutdown(Map.of(VAULT_ID, vault), (id, state) -> {
                    finalFlushStarted.set(true);
                    VaultSaveStore.flushAsync(id, state, dir);
                });
            });
            assertTrue(shutdownStarted.await(10, TimeUnit.SECONDS));
            assertFalse(finalFlushStarted.get());
            releaseWorker.countDown();
            shutdown.get(10, TimeUnit.SECONDS);

            assertTrue(finalFlushStarted.get());
            assertFalse(vault.flushInFlight.get());
            SyncVault recovered = new SyncVault();
            VaultSaveStore.loadVault(dir, VAULT_ID, recovered);
            assertEquals("latest unjournaled update", recovered.cells.get("1,0").getRoomName());
        } finally {
            releaseWorker.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            VaultSaveStore.awaitFlushDrain();
            VaultJournal.closeChannel(VAULT_ID, vault);
        }
    }

    @Test
    void committedRotationAtActivePathCannotOverwriteSnapshot() throws IOException {
        UUID rotation = id(1);
        Path wal = VaultSaveStore.walPath(dir, VAULT_ID);
        // Simulate a power loss that rolled back the .wal -> .wal.old rename.
        Files.write(wal, journal(rotation, cell(1, "old")));
        VaultSaveStore.writeSnapshot(dir, VAULT_ID, Vault.newBuilder().addCells(cell(1, "snapshot only")).build(), rotation);

        SyncVault recovered = new SyncVault();
        VaultSaveStore.loadVault(dir, VAULT_ID, recovered);
        assertEquals("snapshot only", recovered.cells.get("1,0").getRoomName());
        assertFalse(Files.exists(wal)); // subsequent appends must use a fresh journal
        recovered.walFile = wal;
        assertTrue(VaultJournal.appendCell(VAULT_ID, recovered, cell(1, "after restart")));
        assertTrue(VaultJournal.readRotationId(wal).getMostSignificantBits() > rotation.getMostSignificantBits());
        VaultJournal.closeChannel(VAULT_ID, recovered);

        SyncVault recoveredAgain = new SyncVault();
        VaultSaveStore.loadVault(dir, VAULT_ID, recoveredAgain);
        assertEquals("after restart", recoveredAgain.cells.get("1,0").getRoomName());
    }

    @Test
    void committedRotationAtActivePathIsNotRotatedAgain() throws IOException {
        UUID rotation = id(1);
        Path wal = VaultSaveStore.walPath(dir, VAULT_ID);
        Files.write(wal, journal(rotation, cell(1, "old")));
        VaultSaveStore.writeSnapshot(dir, VAULT_ID, Vault.newBuilder().addCells(cell(1, "snapshot only")).build(), rotation);

        VaultSaveStore.reabsorbStrandedWal(VAULT_ID, dir);

        assertFalse(Files.exists(wal));
    }

    @Test
    void legacyWalRestoredAtActivePathCannotOverwriteNewSnapshot() throws IOException {
        Path wal = VaultSaveStore.walPath(dir, VAULT_ID);
        Files.write(wal, legacyJournal(cell(1, "old")));
        VaultSaveStore.writeSnapshot(dir, VAULT_ID, Vault.newBuilder().addCells(cell(1, "snapshot only")).build(), id(1));

        SyncVault recovered = new SyncVault();
        VaultSaveStore.loadVault(dir, VAULT_ID, recovered);

        assertEquals("snapshot only", recovered.cells.get("1,0").getRoomName());
        assertFalse(Files.exists(wal));
        assertTrue(recovered.maxJournalGeneration >= 1);
    }

    @Test
    void earlierRotationCannotOverwriteLaterSnapshot() throws IOException {
        Path old = VaultSaveStore.rotatedWalPath(dir, VAULT_ID);
        Files.write(old, journal(id(1), cell(1, "earlier")));
        // This snapshot absorbed a later rotation. The old one reappeared on disk.
        VaultSaveStore.writeSnapshot(dir, VAULT_ID, Vault.newBuilder().addCells(cell(1, "later")).build(), id(2));

        SyncVault recovered = new SyncVault();
        VaultSaveStore.loadVault(dir, VAULT_ID, recovered);
        assertEquals("later", recovered.cells.get("1,0").getRoomName());

        VaultSaveStore.reabsorbStrandedWal(VAULT_ID, dir);
        assertFalse(Files.exists(old));
    }

    @Test
    void checkpointMetadataCorruptionInvalidatesSnapshot() throws IOException {
        Path snapshot = VaultSaveStore.snapshotPath(dir, VAULT_ID);
        Files.write(VaultSaveStore.rotatedWalPath(dir, VAULT_ID), journal(id(1), cell(1, "journal")));
        VaultSaveStore.writeSnapshot(dir, VAULT_ID, Vault.newBuilder().addCells(cell(1, "snapshot")).build(), id(1));
        byte[] bytes = Files.readAllBytes(snapshot);
        bytes[5] ^= 1; // corrupt the rotation generation, not the protobuf payload
        Files.write(snapshot, bytes);

        SyncVault recovered = new SyncVault();
        VaultSaveStore.loadVault(dir, VAULT_ID, recovered);
        assertEquals("journal", recovered.cells.get("1,0").getRoomName());
    }

    @Test
    void loadsVersionOneSnapshotsWithoutRotationIds() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(1);
        out.write(Vault.newBuilder().addCells(cell(1, "legacy")).build().toByteArray());
        Files.write(VaultSaveStore.snapshotPath(dir, VAULT_ID), bytes.toByteArray());

        SyncVault recovered = new SyncVault();
        VaultSaveStore.loadVault(dir, VAULT_ID, recovered);
        assertEquals("legacy", recovered.cells.get("1,0").getRoomName());
    }

    @Test
    void unreadableSnapshotDoesNotSuppressJournalRecovery() throws IOException {
        UUID rotation = id(1);
        Files.write(VaultSaveStore.rotatedWalPath(dir, VAULT_ID), journal(rotation, cell(1, "journal")));
        Path snapshot = VaultSaveStore.snapshotPath(dir, VAULT_ID);
        VaultSaveStore.writeSnapshot(dir, VAULT_ID, Vault.newBuilder().addCells(cell(1, "snapshot")).build(), rotation);
        Files.write(snapshot, Arrays.copyOf(Files.readAllBytes(snapshot), 21)); // valid header, missing payload length

        SyncVault recovered = new SyncVault();
        VaultSaveStore.loadVault(dir, VAULT_ID, recovered);
        assertEquals("journal", recovered.cells.get("1,0").getRoomName());
    }

    @Test
    void malformedOldRecordCannotHideNewerLiveRecordsAfterMerge() throws IOException {
        Path old = VaultSaveStore.rotatedWalPath(dir, VAULT_ID);
        Path wal = VaultSaveStore.walPath(dir, VAULT_ID);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        byte[] malformed = {(byte) 0xff};
        CRC32 crc = new CRC32();
        crc.update(malformed);
        out.write(VaultJournal.header());
        out.writeInt(malformed.length);
        out.writeInt((int) crc.getValue());
        out.write(malformed);
        Files.write(old, bytes.toByteArray());
        Files.write(wal, journal(cell(1, "new")));

        VaultSaveStore.reabsorbStrandedWal(VAULT_ID, dir);

        SyncVault recovered = new SyncVault();
        VaultJournal.replay(wal, VAULT_ID, recovered);
        assertEquals("new", recovered.cells.get("1,0").getRoomName());
    }

    @Test
    void upgradesVersionOneJournalWithoutLosingItsRecords() throws IOException {
        Path wal = VaultSaveStore.walPath(dir, VAULT_ID);
        Files.write(wal, legacyJournal(cell(1, "legacy journal")));

        UUID id = VaultSaveStore.ensureWalHasRotationId(dir, VAULT_ID);

        assertEquals(id, VaultJournal.readRotationId(wal));
        SyncVault recovered = new SyncVault();
        VaultJournal.replay(wal, VAULT_ID, recovered);
        assertEquals("legacy journal", recovered.cells.get("1,0").getRoomName());
    }

    @Test
    void upgradesEmptyJournalLeftByFailedAppend() throws IOException {
        Path wal = VaultSaveStore.walPath(dir, VAULT_ID);
        Files.write(wal, new byte[0]);

        UUID id = VaultSaveStore.ensureWalHasRotationId(dir, VAULT_ID);

        assertEquals(id, VaultJournal.readRotationId(wal));
        assertEquals(VaultJournal.HEADER_BYTES, Files.size(wal));
    }

    @Test
    void sweepKeepsOldSnapshotWhenJournalIsRecent() throws IOException {
        long now = System.currentTimeMillis();
        long retentionMs = 72L * 3_600_000;
        Path snapshot = VaultSaveStore.snapshotPath(dir, VAULT_ID);
        Path wal = VaultSaveStore.walPath(dir, VAULT_ID);
        Path staging = VaultSaveStore.tmpPath(dir, VAULT_ID);
        Files.write(snapshot, new byte[]{1});
        Files.write(wal, new byte[]{2});
        Files.write(staging, new byte[]{3});
        Files.setLastModifiedTime(snapshot, FileTime.fromMillis(now - retentionMs - 1_000));
        Files.setLastModifiedTime(wal, FileTime.fromMillis(now - 1_000));
        Files.setLastModifiedTime(staging, FileTime.fromMillis(now + retentionMs));

        VaultSaveStore.sweepVaultFilesOnDisk(dir, now, retentionMs, Set.of());
        assertTrue(Files.exists(snapshot));
        assertTrue(Files.exists(wal));

        // A newer staging file cannot extend the retention of the usable save.
        VaultSaveStore.sweepVaultFilesOnDisk(dir, now + retentionMs + 1_000, retentionMs, Set.of());
        assertFalse(Files.exists(snapshot));
        assertFalse(Files.exists(wal));
        assertFalse(Files.exists(staging));
    }

    @Test
    void sweepPrunesOrphanStagingFiles() throws IOException {
        long now = System.currentTimeMillis();
        Path staging = VaultSaveStore.walMergeTmpPath(dir, VAULT_ID);
        Files.write(staging, new byte[]{1});
        Files.setLastModifiedTime(staging, FileTime.fromMillis(now - 10_000));

        VaultSaveStore.sweepVaultFilesOnDisk(dir, now, 5_000, Set.of());

        assertFalse(Files.exists(staging));
    }

    @Test
    void interruptedDeletionNeverLoadsSurvivingJournal() throws Exception {
        // Crash after snapshot deletion, while a newer journal still exists.
        Files.write(VaultSaveStore.deletionPath(dir, VAULT_ID), new byte[]{1});
        Files.write(VaultSaveStore.walPath(dir, VAULT_ID), journal(cell(1, "only half a save")));

        SyncVault directRecovery = new SyncVault();
        assertEquals(0, VaultSaveStore.loadVault(dir, VAULT_ID, directRecovery));
        assertTrue(directRecovery.cells.isEmpty());

        // A new session must finish the interrupted deletion before it can write.
        SyncVault newSession = new SyncVault();
        VaultSaveStore.loadAsync(dir, VAULT_ID, newSession).get(10, TimeUnit.SECONDS);
        assertTrue(newSession.cells.isEmpty());
        assertFalse(Files.exists(VaultSaveStore.walPath(dir, VAULT_ID)));
        assertFalse(Files.exists(VaultSaveStore.deletionPath(dir, VAULT_ID)));
        newSession.putCell(cell(2, "fresh session"));
        VaultSaveStore.appendCell(VAULT_ID, newSession, cell(2, "fresh session"), dir).get(10, TimeUnit.SECONDS);
        VaultJournal.closeChannel(VAULT_ID, newSession);
        SyncVault recoveredAgain = new SyncVault();
        VaultSaveStore.loadVault(dir, VAULT_ID, recoveredAgain);
        assertEquals(Set.of("2,0"), recoveredAgain.cells.keySet());
    }

    @Test
    void failedMultiFileDeletionKeepsMarkerUntilItCanFinish() throws Exception {
        // A nonempty directory stands in for an undeletable snapshot component.
        Path snapshot = VaultSaveStore.snapshotPath(dir, VAULT_ID);
        Files.createDirectory(snapshot);
        Path blocker = snapshot.resolve("blocker");
        Files.write(blocker, new byte[]{1});
        Files.write(VaultSaveStore.rotatedWalPath(dir, VAULT_ID), journal(cell(1, "old")));
        Files.write(VaultSaveStore.walPath(dir, VAULT_ID), journal(cell(2, "new")));

        assertThrows(IOException.class, () -> VaultSaveStore.deleteVaultFiles(dir, VAULT_ID));

        assertTrue(Files.exists(VaultSaveStore.deletionPath(dir, VAULT_ID)));
        assertFalse(Files.exists(VaultSaveStore.rotatedWalPath(dir, VAULT_ID)));
        assertTrue(Files.exists(VaultSaveStore.walPath(dir, VAULT_ID)));
        SyncVault partial = new SyncVault();
        assertThrows(IOException.class, () -> VaultSaveStore.loadVault(dir, VAULT_ID, partial));
        assertFalse(partial.recoveryComplete);
        assertTrue(partial.cells.isEmpty());

        Files.delete(blocker);
        VaultSaveStore.sweepAsync(dir, System.currentTimeMillis(), Long.MAX_VALUE, Set.of()).get(10, TimeUnit.SECONDS);
        assertFalse(Files.exists(snapshot));
        assertFalse(Files.exists(VaultSaveStore.walPath(dir, VAULT_ID)));
        assertFalse(Files.exists(VaultSaveStore.deletionPath(dir, VAULT_ID)));
    }

    @Test
    void shutdownDoesNotOverwriteARecoveryThatFailedToFinishDeletion() throws Exception {
        Path snapshot = VaultSaveStore.snapshotPath(dir, VAULT_ID);
        Files.createDirectory(snapshot);
        Files.write(snapshot.resolve("blocker"), new byte[]{1});
        Files.write(VaultSaveStore.deletionPath(dir, VAULT_ID), new byte[]{1});
        SyncVault vault = new SyncVault();

        assertThrows(ExecutionException.class,
                () -> VaultSaveStore.loadAsync(dir, VAULT_ID, vault).get(10, TimeUnit.SECONDS));
        assertFalse(vault.recoveryComplete);
        AtomicBoolean finalFlushStarted = new AtomicBoolean(false);
        VaultSaveStore.flushAllAtShutdown(Map.of(VAULT_ID, vault), (id, state) -> finalFlushStarted.set(true));

        assertFalse(finalFlushStarted.get());
        assertTrue(Files.exists(snapshot.resolve("blocker")));
        assertTrue(Files.exists(VaultSaveStore.deletionPath(dir, VAULT_ID)));
    }

    @Test
    void periodicSweepRevisitsYoungOrphanStagingFile() throws Exception {
        long now = System.currentTimeMillis();
        Path staging = VaultSaveStore.tmpPath(dir, VAULT_ID);
        Files.write(staging, new byte[]{1});
        Files.setLastModifiedTime(staging, FileTime.fromMillis(now));

        VaultSaveStore.sweepAsync(dir, now, 5_000, Set.of()).get(10, TimeUnit.SECONDS);
        assertTrue(Files.exists(staging));
        VaultSaveStore.sweepAsync(dir, now + 5_001, 5_000, Set.of()).get(10, TimeUnit.SECONDS);
        assertFalse(Files.exists(staging));
    }

    @Test
    void queuedSweepChecksCurrentLiveVaultsRatherThanAnOldCopy() throws Exception {
        long now = System.currentTimeMillis();
        Path snapshot = VaultSaveStore.snapshotPath(dir, VAULT_ID);
        Files.write(snapshot, new byte[]{1});
        Files.setLastModifiedTime(snapshot, FileTime.fromMillis(now - 10_000));
        Set<String> liveVaultIds = ConcurrentHashMap.newKeySet();
        try (BlockedWorker worker = new BlockedWorker()) {
            CompletableFuture<Void> sweep = VaultSaveStore.sweepAsync(dir, now, 5_000, liveVaultIds);
            liveVaultIds.add(VAULT_ID); // a join arrived before the queued sweep ran
            worker.release();
            sweep.get(10, TimeUnit.SECONDS);
        }
        assertTrue(Files.exists(snapshot));
    }

    @Test
    void appendsBypassMaintenanceButRotationStillKeepsFifoBoundary() throws Exception {
        Files.write(VaultSaveStore.rotatedWalPath(dir, VAULT_ID), journal(id(1), cell(1, "old strand")));
        Files.write(VaultSaveStore.walPath(dir, VAULT_ID), journal(id(2), cell(1, "newer on disk")));
        SyncVault vault = new SyncVault();
        VaultSaveStore.loadAsync(dir, VAULT_ID, vault).get(10, TimeUnit.SECONDS);

        try (BlockedWorker worker = new BlockedWorker()) {
            vault.putCell(cell(1, "at snapshot boundary"));
            CompletableFuture<Void> firstAppend = VaultSaveStore.appendCell(VAULT_ID, vault, cell(1, "at snapshot boundary"), dir);
            vault.flushInFlight.set(true);
            VaultSaveStore.flushAsync(VAULT_ID, vault, dir);
            vault.putCell(cell(1, "after boundary"));
            CompletableFuture<Void> laterAppend = VaultSaveStore.appendCell(VAULT_ID, vault, cell(1, "after boundary"), dir);

            firstAppend.get(10, TimeUnit.SECONDS); // journal writes bypass the blocked maintenance worker
            assertFalse(laterAppend.isDone());
            assertFalse(Files.exists(VaultSaveStore.snapshotPath(dir, VAULT_ID)));
            assertTrue(Files.exists(VaultSaveStore.rotatedWalPath(dir, VAULT_ID)));
            assertEquals(journal(id(2), cell(1, "newer on disk"), cell(1, "at snapshot boundary")).length,
                    Files.size(VaultSaveStore.walPath(dir, VAULT_ID)));
            worker.release();
            laterAppend.get(10, TimeUnit.SECONDS);
        }
        assertFalse(vault.flushInFlight.get());
        assertEquals(1, vault.walPending); // later append belongs to the next snapshot
        VaultJournal.closeChannel(VAULT_ID, vault);
        SyncVault recovered = new SyncVault();
        VaultSaveStore.loadVault(dir, VAULT_ID, recovered);
        assertEquals("after boundary", recovered.cells.get("1,0").getRoomName());

        Files.delete(VaultSaveStore.walPath(dir, VAULT_ID));
        SyncVault snapshotOnly = new SyncVault();
        VaultSaveStore.loadVault(dir, VAULT_ID, snapshotOnly);
        assertEquals("at snapshot boundary", snapshotOnly.cells.get("1,0").getRoomName());
    }

    @Test
    void asyncRecoveryReturnsImmediatelyWhileWorkerIsBusy() throws Exception {
        VaultSaveStore.writeSnapshot(dir, VAULT_ID, Vault.newBuilder().addCells(cell(1, "snapshot")).build(), id(1));
        Files.write(VaultSaveStore.walPath(dir, VAULT_ID), journal(id(2), cell(1, "recovered journal")));
        SyncVault vault = new SyncVault();

        try (BlockedWorker worker = new BlockedWorker()) {
            CompletableFuture<Integer> recovery = VaultSaveStore.loadAsync(dir, VAULT_ID, vault);
            assertFalse(recovery.isDone());
            assertFalse(vault.recoveryComplete);
            assertTrue(vault.cells.isEmpty());
            worker.release();
            assertEquals(1, recovery.get(10, TimeUnit.SECONDS));
        }
        assertTrue(vault.recoveryComplete);
        assertEquals("recovered journal", vault.cells.get("1,0").getRoomName());
        assertTrue(vault.walPending >= 100);
    }

    @Test
    void queuedDeletionFinishesBeforeNewSessionRecoversSameVaultId() throws Exception {
        SyncVault oldSession = new SyncVault();
        oldSession.putCell(cell(1, "expired save"));
        VaultSaveStore.appendCell(VAULT_ID, oldSession, cell(1, "expired save"), dir).get(10, TimeUnit.SECONDS);
        SyncVault newSession = new SyncVault();

        try (BlockedWorker worker = new BlockedWorker()) {
            CompletableFuture<Void> deletion = VaultSaveStore.deleteSave(VAULT_ID, oldSession, dir);
            CompletableFuture<Integer> recovery = VaultSaveStore.loadAsync(dir, VAULT_ID, newSession);
            assertFalse(deletion.isDone());
            assertFalse(recovery.isDone());
            worker.release();
            recovery.get(10, TimeUnit.SECONDS);
            deletion.get(10, TimeUnit.SECONDS);
        }
        assertTrue(newSession.cells.isEmpty());
        assertTrue(newSession.recoveryComplete);
        assertNull(oldSession.walChannel);
        assertFalse(Files.exists(VaultSaveStore.walPath(dir, VAULT_ID)));
        assertFalse(Files.exists(VaultSaveStore.deletionPath(dir, VAULT_ID)));
    }

    @Test
    void shutdownWaitsForRecoveryEvenBeforeServerCompletionCallbackRuns() throws Exception {
        VaultSaveStore.writeSnapshot(dir, VAULT_ID, Vault.newBuilder().addCells(cell(1, "saved map")).build(), id(1));
        Files.write(VaultSaveStore.walPath(dir, VAULT_ID), journal(id(2), cell(1, "recovered update")));
        SyncVault vault = new SyncVault();
        vault.loaded = false;
        AtomicBoolean finalFlushStarted = new AtomicBoolean(false);

        try (BlockedWorker worker = new BlockedWorker()) {
            VaultSaveStore.loadAsync(dir, VAULT_ID, vault);
            CompletableFuture<Void> shutdown = CompletableFuture.runAsync(() ->
                    VaultSaveStore.flushAllAtShutdown(Map.of(VAULT_ID, vault), (id, state) -> {
                        finalFlushStarted.set(true);
                        VaultSaveStore.flushAsync(id, state, dir);
                    }));
            worker.release();
            shutdown.get(10, TimeUnit.SECONDS);
        }
        assertFalse(vault.loaded); // callback on the stopping server was never pumped
        assertTrue(finalFlushStarted.get());
        assertTrue(vault.recoveryComplete);
        SyncVault recovered = new SyncVault();
        VaultSaveStore.loadVault(dir, VAULT_ID, recovered);
        assertEquals("recovered update", recovered.cells.get("1,0").getRoomName());
    }

    @Test
    void failedAsyncAppendStillCountsTowardsAMemorySnapshot() throws Exception {
        Path wal = VaultSaveStore.walPath(dir, VAULT_ID);
        Files.createDirectory(wal); // fail opening the append target
        SyncVault vault = new SyncVault();
        vault.putCell(cell(1, "unjournaled but dirty"));

        VaultSaveStore.appendCell(VAULT_ID, vault, cell(1, "unjournaled but dirty"), dir).get(10, TimeUnit.SECONDS);
        assertEquals(1, vault.walPending);
        Files.delete(wal);
        vault.flushInFlight.set(true);
        VaultSaveStore.flushAsync(VAULT_ID, vault, dir);
        VaultSaveStore.awaitFlushDrain();

        assertEquals(0, vault.walPending);
        SyncVault recovered = new SyncVault();
        VaultSaveStore.loadVault(dir, VAULT_ID, recovered);
        assertEquals("unjournaled but dirty", recovered.cells.get("1,0").getRoomName());
    }

    @Test
    void failedWorkerFlushReleasesCellCaptureAndCanBeRetried() throws Exception {
        SyncVault vault = new SyncVault();
        vault.putCell(cell(1, "original"));
        VaultSaveStore.appendCell(VAULT_ID, vault, cell(1, "original"), dir).get(10, TimeUnit.SECONDS);
        Path staging = VaultSaveStore.tmpPath(dir, VAULT_ID);
        Files.createDirectory(staging);
        Files.write(staging.resolve("blocker"), new byte[]{1});

        vault.flushInFlight.set(true);
        VaultSaveStore.flushAsync(VAULT_ID, vault, dir);
        VaultSaveStore.awaitFlushDrain();

        assertFalse(vault.flushInFlight.get());
        assertEquals(1, vault.flushFailures);
        assertTrue(vault.walPending > 0);
        try (SyncVault.CellSnapshot capture = vault.beginCellSnapshot()) {
            assertEquals(1, capture.copyCells().size());
        }
        vault.putCell(cell(1, "updated after failure"));
        VaultSaveStore.appendCell(VAULT_ID, vault, cell(1, "updated after failure"), dir).get(10, TimeUnit.SECONDS);
        Files.delete(staging.resolve("blocker"));
        Files.delete(staging);
        vault.flushInFlight.set(true);
        VaultSaveStore.flushAsync(VAULT_ID, vault, dir);
        VaultSaveStore.awaitFlushDrain();

        assertEquals(0, vault.flushFailures);
        assertEquals(0, vault.walPending);
        SyncVault recovered = new SyncVault();
        VaultSaveStore.loadVault(dir, VAULT_ID, recovered);
        assertEquals("updated after failure", recovered.cells.get("1,0").getRoomName());
    }

    @Test
    void committedWalDeletionFailureBlocksWritesUntilRecoveryRetries() throws Exception {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        UUID generation = id(1);
        Path wal = VaultSaveStore.walPath(dir, VAULT_ID);
        VaultSaveStore.writeSnapshot(dir, VAULT_ID, Vault.newBuilder().addCells(cell(1, "snapshot")).build(), generation);
        Files.write(wal, journal(generation, cell(1, "absorbed record")));
        Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(dir);
        SyncVault vault = new SyncVault();
        try {
            Files.setPosixFilePermissions(dir, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
            assumeTrue(!Files.isWritable(dir), "Directory permissions are ineffective (e.g. running as root)");
            assertTrue(Files.isWritable(wal));
            assertThrows(ExecutionException.class,
                    () -> VaultSaveStore.loadAsync(dir, VAULT_ID, vault).get(10, TimeUnit.SECONDS));
            assertFalse(vault.recoveryComplete);
            long unchangedSize = Files.size(wal);
            assertThrows(ExecutionException.class,
                    () -> VaultSaveStore.appendCell(VAULT_ID, vault, cell(1, "must not be accepted"), dir).get(10, TimeUnit.SECONDS));
            assertEquals(unchangedSize, Files.size(wal));
            AtomicBoolean finalFlushStarted = new AtomicBoolean(false);
            VaultSaveStore.flushAllAtShutdown(Map.of(VAULT_ID, vault), (key, state) -> finalFlushStarted.set(true));
            assertFalse(finalFlushStarted.get());
        } finally {
            Files.setPosixFilePermissions(dir, originalPermissions);
        }
        VaultSaveStore.loadAsync(dir, VAULT_ID, vault).get(10, TimeUnit.SECONDS);
        assertTrue(vault.recoveryComplete);
        assertFalse(Files.exists(wal));
        vault.putCell(cell(1, "after retry"));
        VaultSaveStore.appendCell(VAULT_ID, vault, cell(1, "after retry"), dir).get(10, TimeUnit.SECONDS);
        VaultJournal.closeChannel(VAULT_ID, vault);
        SyncVault recovered = new SyncVault();
        VaultSaveStore.loadVault(dir, VAULT_ID, recovered);
        assertEquals("after retry", recovered.cells.get("1,0").getRoomName());
    }

    @Test
    void unsupportedDirectorySyncRetainsCutoffAndAllowsNewerSaves() throws Exception {
        VaultSaveStore.DirectorySync unsupported = path -> false; // standard Windows JDK behavior
        VaultSaveStore.writeSnapshot(dir, VAULT_ID, Vault.newBuilder().addCells(cell(1, "expired snapshot")).build(), id(1));
        byte[] expiredWal = journal(id(2), cell(1, "expired journal"));
        Files.write(VaultSaveStore.walPath(dir, VAULT_ID), expiredWal);

        VaultSaveStore.deleteVaultFiles(dir, VAULT_ID, unsupported);

        assertFalse(Files.exists(VaultSaveStore.snapshotPath(dir, VAULT_ID)));
        assertFalse(Files.exists(VaultSaveStore.walPath(dir, VAULT_ID)));
        assertEquals(16, Files.size(VaultSaveStore.deletionPath(dir, VAULT_ID)));
        // Even if an old unlink rolls back, the retained cutoff prevents recovery.
        Files.write(VaultSaveStore.walPath(dir, VAULT_ID), expiredWal);
        SyncVault oldRecovery = new SyncVault();
        VaultSaveStore.loadVault(dir, VAULT_ID, oldRecovery);
        assertTrue(oldRecovery.cells.isEmpty());

        SyncVault fresh = new SyncVault();
        VaultSaveStore.loadAsync(dir, VAULT_ID, fresh, unsupported).get(10, TimeUnit.SECONDS);
        fresh.putCell(cell(3, "fresh cell"));
        VaultSaveStore.appendCell(VAULT_ID, fresh, cell(3, "fresh cell"), dir).get(10, TimeUnit.SECONDS);
        VaultJournal.closeChannel(VAULT_ID, fresh);
        assertTrue(VaultJournal.readRotationId(VaultSaveStore.walPath(dir, VAULT_ID)).getMostSignificantBits() > 2);

        SyncVault restarted = new SyncVault();
        VaultSaveStore.loadAsync(dir, VAULT_ID, restarted, unsupported).get(10, TimeUnit.SECONDS);
        assertEquals(Set.of("3,0"), restarted.cells.keySet());
        restarted.flushInFlight.set(true);
        VaultSaveStore.flushAsync(VAULT_ID, restarted, dir);
        VaultSaveStore.awaitFlushDrain();
        SyncVault snapshotRecovery = new SyncVault();
        VaultSaveStore.loadAsync(dir, VAULT_ID, snapshotRecovery, unsupported).get(10, TimeUnit.SECONDS);
        assertEquals("fresh cell", snapshotRecovery.cells.get("3,0").getRoomName());
    }

    @Test
    void importedLegacyDeletionMarkerWorksWithoutDirectorySync() throws Exception {
        Files.write(VaultSaveStore.deletionPath(dir, VAULT_ID), new byte[]{1});
        Files.write(VaultSaveStore.walPath(dir, VAULT_ID), journal(id(7), cell(1, "half-deleted save")));
        SyncVault vault = new SyncVault();

        VaultSaveStore.loadAsync(dir, VAULT_ID, vault, path -> false).get(10, TimeUnit.SECONDS);

        assertTrue(vault.recoveryComplete);
        assertTrue(vault.cells.isEmpty());
        assertFalse(Files.exists(VaultSaveStore.walPath(dir, VAULT_ID)));
        assertEquals(16, Files.size(VaultSaveStore.deletionPath(dir, VAULT_ID)));
        assertTrue(vault.maxJournalGeneration >= 7);
        // A snapshot-only fresh save also needs a generation above the cutoff.
        vault.putCell(cell(2, "snapshot-only new cell"));
        vault.flushInFlight.set(true);
        VaultSaveStore.flushAsync(VAULT_ID, vault, dir);
        VaultSaveStore.awaitFlushDrain();
        SyncVault restarted = new SyncVault();
        VaultSaveStore.loadAsync(dir, VAULT_ID, restarted, path -> false).get(10, TimeUnit.SECONDS);
        assertEquals("snapshot-only new cell", restarted.cells.get("2,0").getRoomName());
    }

    @Test
    void supportedDirectorySyncFailureDoesNotStartPartialDeletion() throws Exception {
        Path wal = VaultSaveStore.walPath(dir, VAULT_ID);
        Files.write(wal, journal(cell(1, "preserved")));
        VaultSaveStore.DirectorySync failing = path -> { throw new IOException("injected directory sync failure"); };

        assertThrows(IOException.class, () -> VaultSaveStore.deleteVaultFiles(dir, VAULT_ID, failing));

        assertTrue(Files.exists(wal));
        assertFalse(Files.exists(VaultSaveStore.deletionPath(dir, VAULT_ID)));
    }

    @Test
    void directorySyncFailureAfterMarkerLeavesRecoveriesSuppressed() throws Exception {
        Path wal = VaultSaveStore.walPath(dir, VAULT_ID);
        Files.write(wal, journal(cell(1, "old")));
        java.util.concurrent.atomic.AtomicInteger forces = new java.util.concurrent.atomic.AtomicInteger();
        VaultSaveStore.DirectorySync failing = path -> {
            if (forces.incrementAndGet() == 2) throw new IOException("injected post-marker failure");
            return true;
        };

        assertThrows(IOException.class, () -> VaultSaveStore.deleteVaultFiles(dir, VAULT_ID, failing));

        assertTrue(Files.exists(wal));
        assertTrue(Files.exists(VaultSaveStore.deletionPath(dir, VAULT_ID)));
        SyncVault recovered = new SyncVault();
        VaultSaveStore.loadVault(dir, VAULT_ID, recovered);
        assertTrue(recovered.cells.isEmpty());
    }

    @Test
    void directorySyncFailureAfterComponentsAreRemovedKeepsSafetyRecord() throws Exception {
        Path wal = VaultSaveStore.walPath(dir, VAULT_ID);
        Files.write(wal, journal(cell(1, "old")));
        java.util.concurrent.atomic.AtomicInteger forces = new java.util.concurrent.atomic.AtomicInteger();
        VaultSaveStore.DirectorySync failing = path -> {
            // Preflight, after creating the marker, before removal, after removal.
            if (forces.incrementAndGet() == 4) throw new IOException("injected post-removal failure");
            return true;
        };

        assertThrows(IOException.class, () -> VaultSaveStore.deleteVaultFiles(dir, VAULT_ID, failing));

        assertFalse(Files.exists(wal));
        assertTrue(Files.exists(VaultSaveStore.deletionPath(dir, VAULT_ID)));
        SyncVault vault = new SyncVault();
        VaultSaveStore.loadAsync(dir, VAULT_ID, vault).get(10, TimeUnit.SECONDS);
        assertTrue(vault.recoveryComplete);
        assertTrue(vault.cells.isEmpty());
        assertFalse(Files.exists(VaultSaveStore.deletionPath(dir, VAULT_ID)));
    }

    @Test
    void tornDeletionRecordCannotReleaseRecoveryOrAcceptWrites() throws Exception {
        Files.write(VaultSaveStore.walPath(dir, VAULT_ID), journal(cell(1, "possibly expired")));
        Files.write(VaultSaveStore.deletionPath(dir, VAULT_ID), new byte[]{0, 0, 0, 1, 0});
        SyncVault vault = new SyncVault();

        assertThrows(ExecutionException.class,
                () -> VaultSaveStore.loadAsync(dir, VAULT_ID, vault).get(10, TimeUnit.SECONDS));
        assertFalse(vault.recoveryComplete);
        assertThrows(ExecutionException.class,
                () -> VaultSaveStore.appendCell(VAULT_ID, vault, cell(2, "blocked"), dir).get(10, TimeUnit.SECONDS));
        assertTrue(vault.cells.isEmpty());
    }

    @Test
    void repeatedPortableDeletionAdvancesStickyCutoffForLaterSession() throws Exception {
        VaultSaveStore.DirectorySync unsupported = path -> false;
        Files.write(VaultSaveStore.walPath(dir, VAULT_ID), journal(id(2), cell(1, "first expired session")));
        VaultSaveStore.deleteVaultFiles(dir, VAULT_ID, unsupported);
        SyncVault secondSession = new SyncVault();
        VaultSaveStore.loadAsync(dir, VAULT_ID, secondSession, unsupported).get(10, TimeUnit.SECONDS);
        VaultSaveStore.appendCell(VAULT_ID, secondSession, cell(2, "second expired session"), dir).get(10, TimeUnit.SECONDS);
        VaultJournal.closeChannel(VAULT_ID, secondSession);
        byte[] secondJournal = Files.readAllBytes(VaultSaveStore.walPath(dir, VAULT_ID));

        VaultSaveStore.deleteVaultFiles(dir, VAULT_ID, unsupported);
        Files.write(VaultSaveStore.walPath(dir, VAULT_ID), secondJournal); // rolled-back unlink
        SyncVault thirdSession = new SyncVault();
        VaultSaveStore.loadAsync(dir, VAULT_ID, thirdSession, unsupported).get(10, TimeUnit.SECONDS);
        assertTrue(thirdSession.cells.isEmpty());
        VaultSaveStore.appendCell(VAULT_ID, thirdSession, cell(3, "third session"), dir).get(10, TimeUnit.SECONDS);
        VaultJournal.closeChannel(VAULT_ID, thirdSession);
        SyncVault recovered = new SyncVault();
        VaultSaveStore.loadAsync(dir, VAULT_ID, recovered, unsupported).get(10, TimeUnit.SECONDS);
        assertEquals(Set.of("3,0"), recovered.cells.keySet());
    }

    @Test
    void writableButUnreadableCommittedWalCannotPassRecovery() throws Exception {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path wal = VaultSaveStore.walPath(dir, VAULT_ID);
        VaultSaveStore.writeSnapshot(dir, VAULT_ID, Vault.newBuilder().addCells(cell(1, "snapshot")).build(), id(1));
        Files.write(wal, journal(id(1), cell(1, "absorbed")));
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(wal);
        SyncVault vault = new SyncVault();
        try {
            Files.setPosixFilePermissions(wal, Set.of(PosixFilePermission.OWNER_WRITE));
            assumeTrue(!Files.isReadable(wal), "File permissions are ineffective (e.g. running as root)");
            assertTrue(Files.isWritable(wal));
            assertThrows(ExecutionException.class,
                    () -> VaultSaveStore.loadAsync(dir, VAULT_ID, vault).get(10, TimeUnit.SECONDS));
            assertFalse(vault.recoveryComplete);
            assertThrows(ExecutionException.class,
                    () -> VaultSaveStore.appendCell(VAULT_ID, vault, cell(1, "blocked update"), dir).get(10, TimeUnit.SECONDS));
        } finally {
            Files.setPosixFilePermissions(wal, permissions);
        }
        VaultSaveStore.loadAsync(dir, VAULT_ID, vault).get(10, TimeUnit.SECONDS);
        assertTrue(vault.recoveryComplete);
        assertFalse(Files.exists(wal));
        VaultSaveStore.appendCell(VAULT_ID, vault, cell(1, "after recovery retry"), dir).get(10, TimeUnit.SECONDS);
        VaultJournal.closeChannel(VAULT_ID, vault);
        SyncVault restarted = new SyncVault();
        VaultSaveStore.loadVault(dir, VAULT_ID, restarted);
        assertEquals("after recovery retry", restarted.cells.get("1,0").getRoomName());
    }

    @Test
    void unreadableSnapshotBlocksRecoveryInsteadOfFallingBackToOnlyJournals() throws Exception {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path snapshot = VaultSaveStore.snapshotPath(dir, VAULT_ID);
        VaultSaveStore.writeSnapshot(dir, VAULT_ID, Vault.newBuilder().addCells(cell(1, "snapshot-only cell")).build(), id(1));
        Files.write(VaultSaveStore.walPath(dir, VAULT_ID), journal(id(2), cell(2, "journal cell")));
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(snapshot);
        SyncVault vault = new SyncVault();
        try {
            Files.setPosixFilePermissions(snapshot, Set.of(PosixFilePermission.OWNER_WRITE));
            assumeTrue(!Files.isReadable(snapshot));
            assertThrows(ExecutionException.class,
                    () -> VaultSaveStore.loadAsync(dir, VAULT_ID, vault).get(10, TimeUnit.SECONDS));
            assertFalse(vault.recoveryComplete);
        } finally {
            Files.setPosixFilePermissions(snapshot, permissions);
        }
        VaultSaveStore.loadAsync(dir, VAULT_ID, vault).get(10, TimeUnit.SECONDS);
        assertTrue(vault.recoveryComplete);
        assertEquals(Set.of("1,0", "2,0"), vault.cells.keySet());
    }

    @Test
    void failedJournalTailRepairKeepsRecoveryIncompleteUntilRetry() throws Exception {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path wal = VaultSaveStore.walPath(dir, VAULT_ID);
        byte[] good = journal(id(1), cell(1, "intact record"));
        byte[] broken = Arrays.copyOf(good, good.length + 3);
        Files.write(wal, broken);
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(wal);
        SyncVault vault = new SyncVault();
        try {
            Files.setPosixFilePermissions(wal, Set.of(PosixFilePermission.OWNER_READ));
            assumeTrue(!Files.isWritable(wal));
            assertThrows(ExecutionException.class,
                    () -> VaultSaveStore.loadAsync(dir, VAULT_ID, vault).get(10, TimeUnit.SECONDS));
            assertFalse(vault.recoveryComplete);
            assertEquals(broken.length, Files.size(wal));
        } finally {
            Files.setPosixFilePermissions(wal, permissions);
        }
        VaultSaveStore.loadAsync(dir, VAULT_ID, vault).get(10, TimeUnit.SECONDS);
        assertTrue(vault.recoveryComplete);
        assertEquals(good.length, Files.size(wal));
        assertEquals("intact record", vault.cells.get("1,0").getRoomName());
    }

    @Test
    void unsearchableSaveDirectoryIsNotMistakenForMissingFiles() throws Exception {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Files.write(VaultSaveStore.walPath(dir, VAULT_ID), journal(cell(1, "saved")));
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(dir);
        SyncVault vault = new SyncVault();
        try {
            Files.setPosixFilePermissions(dir, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            assumeTrue(!Files.isExecutable(dir));
            assertThrows(ExecutionException.class,
                    () -> VaultSaveStore.loadAsync(dir, VAULT_ID, vault).get(10, TimeUnit.SECONDS));
            assertFalse(vault.recoveryComplete);
        } finally {
            Files.setPosixFilePermissions(dir, permissions);
        }
        VaultSaveStore.loadAsync(dir, VAULT_ID, vault).get(10, TimeUnit.SECONDS);
        assertTrue(vault.recoveryComplete);
        assertEquals("saved", vault.cells.get("1,0").getRoomName());
    }

    @Test
    void genuinelyCorruptJournalHeaderCanStillBeRepairedAndAcceptFreshWrites() throws Exception {
        Path wal = VaultSaveStore.walPath(dir, VAULT_ID);
        VaultSaveStore.writeSnapshot(dir, VAULT_ID, Vault.newBuilder().addCells(cell(1, "snapshot")).build(), id(1));
        Files.write(wal, new byte[]{1, 2, 3});
        SyncVault vault = new SyncVault();
        VaultSaveStore.loadAsync(dir, VAULT_ID, vault).get(10, TimeUnit.SECONDS);

        assertTrue(vault.recoveryComplete);
        assertEquals(0, Files.size(wal));
        VaultSaveStore.appendCell(VAULT_ID, vault, cell(1, "new record"), dir).get(10, TimeUnit.SECONDS);
        VaultJournal.closeChannel(VAULT_ID, vault);
        SyncVault restarted = new SyncVault();
        VaultSaveStore.loadVault(dir, VAULT_ID, restarted);
        assertEquals("new record", restarted.cells.get("1,0").getRoomName());
    }

    @Test
    void interruptedInitialDeletionStagingLeavesEntireOriginalSaveRecoverable() throws Exception {
        VaultSaveStore.writeSnapshot(dir, VAULT_ID, Vault.newBuilder().addCells(cell(1, "snapshot")).build(), id(1));
        Files.write(VaultSaveStore.walPath(dir, VAULT_ID), journal(id(2), cell(2, "journal")));
        // Process interruption after creating the staging file, before writing it.
        Path staging = VaultSaveStore.deletionTmpPath(dir, VAULT_ID);
        Files.write(staging, new byte[0]);
        SyncVault vault = new SyncVault();
        VaultSaveStore.loadAsync(dir, VAULT_ID, vault).get(10, TimeUnit.SECONDS);

        assertTrue(vault.recoveryComplete);
        assertEquals(Set.of("1,0", "2,0"), vault.cells.keySet());
        assertFalse(Files.exists(VaultSaveStore.deletionPath(dir, VAULT_ID)));
        Files.setLastModifiedTime(staging, FileTime.fromMillis(System.currentTimeMillis() - 10_000));
        VaultSaveStore.sweepAsync(dir, System.currentTimeMillis(), 5_000, Set.of(VAULT_ID)).get(10, TimeUnit.SECONDS);
        assertTrue(Files.exists(staging)); // live saves must not be pruned
        VaultSaveStore.sweepAsync(dir, System.currentTimeMillis(), 5_000, Set.of()).get(10, TimeUnit.SECONDS);
        assertFalse(Files.exists(staging));
    }

    @Test
    void interruptedMarkerUpdateKeepsPreviousCutoffAndNewerRecordsRecoverable() throws Exception {
        VaultSaveStore.DirectorySync unsupported = path -> false;
        Files.write(VaultSaveStore.walPath(dir, VAULT_ID), journal(id(1), cell(1, "retired")));
        VaultSaveStore.deleteVaultFiles(dir, VAULT_ID, unsupported);
        byte[] priorMarker = Files.readAllBytes(VaultSaveStore.deletionPath(dir, VAULT_ID));
        Files.write(VaultSaveStore.walPath(dir, VAULT_ID), journal(id(2), cell(2, "not yet retired")));
        // Interrupted halfway through encoding the replacement cutoff.
        Files.write(VaultSaveStore.deletionTmpPath(dir, VAULT_ID), Arrays.copyOf(deletionRecord(2), 6));
        SyncVault vault = new SyncVault();
        VaultSaveStore.loadAsync(dir, VAULT_ID, vault, unsupported).get(10, TimeUnit.SECONDS);

        assertTrue(vault.recoveryComplete);
        assertEquals(Set.of("2,0"), vault.cells.keySet());
        assertTrue(Arrays.equals(priorMarker, Files.readAllBytes(VaultSaveStore.deletionPath(dir, VAULT_ID))));
        assertFalse(Files.exists(VaultSaveStore.deletionTmpPath(dir, VAULT_ID)));
    }

    @Test
    void failedMarkerStagingCannotOverwriteLiveCutoffOrDeleteNewerFiles() throws Exception {
        VaultSaveStore.DirectorySync unsupported = path -> false;
        Path wal = VaultSaveStore.walPath(dir, VAULT_ID);
        Files.write(wal, journal(id(1), cell(1, "retired")));
        VaultSaveStore.deleteVaultFiles(dir, VAULT_ID, unsupported);
        byte[] priorMarker = Files.readAllBytes(VaultSaveStore.deletionPath(dir, VAULT_ID));
        Files.write(wal, journal(id(2), cell(2, "still recoverable")));
        Path staging = VaultSaveStore.deletionTmpPath(dir, VAULT_ID);
        Files.createDirectory(staging);

        assertThrows(IOException.class, () -> VaultSaveStore.deleteVaultFiles(dir, VAULT_ID, unsupported));
        assertTrue(Arrays.equals(priorMarker, Files.readAllBytes(VaultSaveStore.deletionPath(dir, VAULT_ID))));
        assertTrue(Files.exists(wal));
        SyncVault recovery = new SyncVault();
        VaultSaveStore.loadVault(dir, VAULT_ID, recovery);
        assertEquals(Set.of("2,0"), recovery.cells.keySet());

        Files.delete(staging);
        VaultSaveStore.deleteVaultFiles(dir, VAULT_ID, unsupported);
        SyncVault afterRetry = new SyncVault();
        VaultSaveStore.loadAsync(dir, VAULT_ID, afterRetry, unsupported).get(10, TimeUnit.SECONDS);
        assertTrue(afterRetry.recoveryComplete);
        assertTrue(afterRetry.cells.isEmpty());
    }

    @Test
    void completedButUninstalledMarkerStagingIsNotACommittedDeletion() throws Exception {
        Files.write(VaultSaveStore.walPath(dir, VAULT_ID), journal(id(2), cell(1, "saved record")));
        Files.write(VaultSaveStore.deletionTmpPath(dir, VAULT_ID), deletionRecord(2));
        SyncVault vault = new SyncVault();
        VaultSaveStore.loadAsync(dir, VAULT_ID, vault).get(10, TimeUnit.SECONDS);

        assertTrue(vault.recoveryComplete);
        assertEquals("saved record", vault.cells.get("1,0").getRoomName());
        assertFalse(Files.exists(VaultSaveStore.deletionPath(dir, VAULT_ID)));
    }

    private static byte[] deletionRecord(long generation) {
        ByteBuffer bytes = ByteBuffer.allocate(16).putInt(1).putLong(generation);
        CRC32 crc = new CRC32();
        crc.update(bytes.array(), 0, 12);
        bytes.putInt((int) crc.getValue());
        return bytes.array();
    }

    @Test
    void forcedBatchAndReceiptRecoverTogetherWithoutASnapshot() throws Exception {
        SyncVault vault = new SyncVault();
        UUID source = UUID.randomUUID();
        var cells = java.util.List.of(cell(1, "first"), cell(2, "second"));
        cells.forEach(vault::putCell);
        VaultSaveStore.appendUpdate(VAULT_ID, vault, source, 1, cells, dir).get(10, TimeUnit.SECONDS);
        assertEquals(1L, vault.durableReceipts.get(source));
        VaultJournal.closeChannel(VAULT_ID, vault);
        assertFalse(Files.exists(VaultSaveStore.snapshotPath(dir, VAULT_ID)));
        SyncVault recovered = new SyncVault();
        VaultSaveStore.loadAsync(dir, VAULT_ID, recovered).get(10, TimeUnit.SECONDS);
        assertEquals(Set.of("1,0", "2,0"), recovered.cells.keySet());
        assertEquals(1L, recovered.durableReceipts.get(source));
    }

    @Test
    void compactedReceiptPreventsLostAckRetryFromRollingBackLaterUpdate() throws Exception {
        SyncVault vault = new SyncVault();
        UUID firstSource = UUID.randomUUID();
        UUID secondSource = UUID.randomUUID();
        var old = java.util.List.of(cell(1, "first source"));
        old.forEach(vault::putCell);
        VaultSaveStore.appendUpdate(VAULT_ID, vault, firstSource, 1, old, dir).get(10, TimeUnit.SECONDS);
        var newer = java.util.List.of(cell(1, "later source"));
        newer.forEach(vault::putCell);
        VaultSaveStore.appendUpdate(VAULT_ID, vault, secondSource, 1, newer, dir).get(10, TimeUnit.SECONDS);
        vault.flushInFlight.set(true);
        VaultSaveStore.flushAsync(VAULT_ID, vault, dir);
        VaultSaveStore.awaitFlushDrain();
        SyncVault recovered = new SyncVault();
        VaultSaveStore.loadAsync(dir, VAULT_ID, recovered).get(10, TimeUnit.SECONDS);
        assertEquals(1L, recovered.durableReceipts.get(firstSource));
        assertEquals(1L, recovered.durableReceipts.get(secondSource));
        VaultSaveStore.appendUpdate(VAULT_ID, recovered, firstSource, 1, old, dir).get(10, TimeUnit.SECONDS);
        assertEquals("later source", recovered.cells.get("1,0").getRoomName());
        SyncVault again = new SyncVault();
        VaultSaveStore.loadAsync(dir, VAULT_ID, again).get(10, TimeUnit.SECONDS);
        assertEquals("later source", again.cells.get("1,0").getRoomName());
    }

    @Test
    void tornBatchCannotRecoverHalfItsCellsOrAdvanceItsReceipt() throws Exception {
        SyncVault vault = new SyncVault();
        UUID source = UUID.randomUUID();
        var cells = java.util.List.of(cell(1, "first"), cell(2, "second"));
        cells.forEach(vault::putCell);
        VaultSaveStore.appendUpdate(VAULT_ID, vault, source, 1, cells, dir).get(10, TimeUnit.SECONDS);
        VaultJournal.closeChannel(VAULT_ID, vault);
        Path wal = VaultSaveStore.walPath(dir, VAULT_ID);
        byte[] bytes = Files.readAllBytes(wal);
        Files.write(wal, Arrays.copyOf(bytes, bytes.length - 1));
        SyncVault recovered = new SyncVault();
        VaultSaveStore.loadAsync(dir, VAULT_ID, recovered).get(10, TimeUnit.SECONDS);
        assertTrue(recovered.cells.isEmpty());
        assertFalse(recovered.durableReceipts.containsKey(source));
        assertEquals(VaultJournal.HEADER_BYTES, Files.size(wal));
        cells.forEach(recovered::putCell);
        VaultSaveStore.appendUpdate(VAULT_ID, recovered, source, 1, cells, dir).get(10, TimeUnit.SECONDS);
        VaultJournal.closeChannel(VAULT_ID, recovered);
        SyncVault again = new SyncVault();
        VaultSaveStore.loadAsync(dir, VAULT_ID, again).get(10, TimeUnit.SECONDS);
        assertEquals(2, again.cells.size());
        assertEquals(1L, again.durableReceipts.get(source));
    }

    @Test
    void failedBatchRemainsUnacknowledgedUntilDiskRetrySucceeds() throws Exception {
        SyncVault vault = new SyncVault();
        UUID source = UUID.randomUUID();
        var cells = java.util.List.of(cell(1, "pending"));
        cells.forEach(vault::putCell);
        Path wal = VaultSaveStore.walPath(dir, VAULT_ID);
        Files.createDirectory(wal);
        assertThrows(ExecutionException.class,
                () -> VaultSaveStore.appendUpdate(VAULT_ID, vault, source, 1, cells, dir).get(10, TimeUnit.SECONDS));
        assertFalse(vault.durableReceipts.containsKey(source));
        Files.delete(wal);
        VaultSaveStore.appendUpdate(VAULT_ID, vault, source, 1, cells, dir).get(10, TimeUnit.SECONDS);
        assertEquals(1L, vault.durableReceipts.get(source));
        VaultJournal.closeChannel(VAULT_ID, vault);
    }

    @Test
    void durableNewerAppendsDoNotWaitForBlockedSnapshotWriter() throws Exception {
        SyncVault vault = new SyncVault();
        UUID source = UUID.randomUUID();
        var before = java.util.List.of(cell(1, "snapshot boundary"));
        before.forEach(vault::putCell);
        VaultSaveStore.appendUpdate(VAULT_ID, vault, source, 1, before, dir).get(10, TimeUnit.SECONDS);
        try (BlockedWorker worker = new BlockedWorker(VaultSaveStore.SNAPSHOT_EXECUTOR)) {
            vault.flushInFlight.set(true);
            VaultSaveStore.flushAsync(VAULT_ID, vault, dir);
            var later = java.util.List.of(cell(1, "after boundary"));
            later.forEach(vault::putCell);
            VaultSaveStore.appendUpdate(VAULT_ID, vault, source, 2, later, dir).get(10, TimeUnit.SECONDS);
            assertEquals(2L, vault.durableReceipts.get(source));
            assertTrue(vault.flushInFlight.get());
            assertFalse(Files.exists(VaultSaveStore.snapshotPath(dir, VAULT_ID)));
            worker.release();
        }
        assertEquals(1, vault.walPending);
        VaultJournal.closeChannel(VAULT_ID, vault);
        SyncVault full = new SyncVault();
        VaultSaveStore.loadAsync(dir, VAULT_ID, full).get(10, TimeUnit.SECONDS);
        assertEquals("after boundary", full.cells.get("1,0").getRoomName());
        assertEquals(2L, full.durableReceipts.get(source));
        Files.delete(VaultSaveStore.walPath(dir, VAULT_ID));
        SyncVault snapshotOnly = new SyncVault();
        VaultSaveStore.loadAsync(dir, VAULT_ID, snapshotOnly).get(10, TimeUnit.SECONDS);
        assertEquals("snapshot boundary", snapshotOnly.cells.get("1,0").getRoomName());
        assertEquals(1L, snapshotOnly.durableReceipts.get(source));
    }

    @Test
    void versionTwoSaveUpgradesBeforeAcceptingReceiptBatches() throws Exception {
        ByteArrayOutputStream snapshot = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(snapshot);
        byte[] proto = Vault.newBuilder().addCells(cell(1, "v2 snapshot")).build().toByteArray();
        out.writeInt(2);
        out.writeBoolean(true);
        out.writeLong(1);
        out.writeLong(1234);
        out.writeInt(proto.length);
        out.write(proto);
        CRC32 crc = new CRC32();
        crc.update(snapshot.toByteArray());
        out.writeInt((int) crc.getValue());
        Files.write(VaultSaveStore.snapshotPath(dir, VAULT_ID), snapshot.toByteArray());
        byte[] journal = journal(id(2), cell(2, "v2 journal"));
        ByteBuffer.wrap(journal).putInt(4, 2);
        crc.reset();
        crc.update(journal, 0, 24);
        ByteBuffer.wrap(journal).putInt(24, (int) crc.getValue());
        Files.write(VaultSaveStore.walPath(dir, VAULT_ID), journal);
        SyncVault vault = new SyncVault();
        VaultSaveStore.loadAsync(dir, VAULT_ID, vault).get(10, TimeUnit.SECONDS);
        assertEquals(Set.of("1,0", "2,0"), vault.cells.keySet());
        assertEquals(3, VaultJournal.readHeader(VaultSaveStore.walPath(dir, VAULT_ID)).version());
        UUID source = UUID.randomUUID();
        var batch = java.util.List.of(cell(3, "new batch"));
        batch.forEach(vault::putCell);
        VaultSaveStore.appendUpdate(VAULT_ID, vault, source, 1, batch, dir).get(10, TimeUnit.SECONDS);
        VaultJournal.closeChannel(VAULT_ID, vault);
        SyncVault again = new SyncVault();
        VaultSaveStore.loadAsync(dir, VAULT_ID, again).get(10, TimeUnit.SECONDS);
        assertEquals(Set.of("1,0", "2,0", "3,0"), again.cells.keySet());
        assertEquals(1L, again.durableReceipts.get(source));
    }

    @Test
    void queuedOldSessionWriteDeletionAndNewRecoveryShareOneFileLane() throws Exception {
        SyncVault oldSession = new SyncVault();
        UUID source = UUID.randomUUID();
        var cells = java.util.List.of(cell(1, "expired session"));
        cells.forEach(oldSession::putCell);
        SyncVault newSession = new SyncVault();
        try (BlockedWorker worker = new BlockedWorker(VaultSaveStore.JOURNAL_EXECUTOR)) {
            var write = VaultSaveStore.appendUpdate(VAULT_ID, oldSession, source, 1, cells, dir);
            var deletion = VaultSaveStore.deleteSave(VAULT_ID, oldSession, dir);
            var recovery = VaultSaveStore.loadAsync(dir, VAULT_ID, newSession);
            assertFalse(write.isDone());
            assertFalse(deletion.isDone());
            assertFalse(recovery.isDone());
            worker.release();
            recovery.get(10, TimeUnit.SECONDS);
        }
        assertTrue(newSession.cells.isEmpty());
        assertTrue(newSession.durableReceipts.isEmpty());
        assertTrue(newSession.recoveryComplete);
        assertNull(oldSession.walChannel);
    }

    @Test
    void recoveredReceiptsCountTowardAdmissionBeforeAnyNewSourceIsApplied() throws Exception {
        Map<UUID, Long> receipts = new java.util.HashMap<>();
        for (int i = 0; i < 4096; i++) receipts.put(UUID.randomUUID(), 1L);
        VaultSaveStore.writeSnapshot(dir, VAULT_ID, Vault.getDefaultInstance(), id(1), receipts);
        SyncVault recovered = new SyncVault();
        VaultSaveStore.loadAsync(dir, VAULT_ID, recovered).get(10, TimeUnit.SECONDS);
        assertEquals(4096, recovered.acceptedSequences.size());
        assertFalse(recovered.acceptsSource(UUID.randomUUID()));
        assertTrue(recovered.acceptsSource(receipts.keySet().iterator().next()));
        assertTrue(recovered.cells.isEmpty());
    }

    @Test
    void expiryForgetsReceiptsAndNegotiatesRestartInsteadOfExpectingOldSequence() throws Exception {
        SyncVault vault = new SyncVault();
        UUID source = UUID.randomUUID();
        var cells = java.util.List.of(cell(1, "expired"));
        cells.forEach(vault::putCell);
        VaultSaveStore.appendUpdate(VAULT_ID, vault, source, 1, cells, dir).get(10, TimeUnit.SECONDS);
        VaultSaveStore.deleteSave(VAULT_ID, vault, dir).get(10, TimeUnit.SECONDS);
        SyncVault fresh = new SyncVault();
        VaultSaveStore.loadAsync(dir, VAULT_ID, fresh).get(10, TimeUnit.SECONDS);
        assertTrue(fresh.needsStreamReset(source, 2));
        UUID newSource = UUID.randomUUID();
        var newCells = java.util.List.of(cell(2, "new outstanding"));
        newCells.forEach(fresh::putCell);
        VaultSaveStore.appendUpdate(VAULT_ID, fresh, newSource, 1, newCells, dir).get(10, TimeUnit.SECONDS);
        assertEquals(1L, fresh.durableReceipts.get(newSource));
        assertFalse(fresh.needsStreamReset(newSource, 2));
        VaultJournal.closeChannel(VAULT_ID, fresh);
    }

    @Test
    void rotationCannotClearDirtyCountForPostBoundaryRejectedWrites() throws Exception {
        SyncVault vault = new SyncVault();
        UUID source = UUID.randomUUID();
        var before = java.util.List.of(cell(1, "boundary"));
        before.forEach(vault::putCell);
        VaultSaveStore.appendUpdate(VAULT_ID, vault, source, 1, before, dir).get(10, TimeUnit.SECONDS);
        try (BlockedWorker worker = new BlockedWorker()) {
            vault.flushInFlight.set(true);
            VaultSaveStore.flushAsync(VAULT_ID, vault, dir);
            Path path = VaultSaveStore.walPath(dir, VAULT_ID);
            for (int i = 0; i < 255; i++) {
                VaultIoQueue.submit(path, VaultSaveStore.JOURNAL_EXECUTOR, () -> null);
            }
            var after = java.util.List.of(cell(2, "rejected after boundary"));
            after.forEach(vault::putCell);
            var rejected = VaultSaveStore.appendUpdate(VAULT_ID, vault, UUID.randomUUID(), 1, after, dir);
            assertTrue(rejected.isCompletedExceptionally());
            assertEquals(2, vault.walPending);
            worker.release();
        }
        assertEquals(1, vault.walPending);
        SyncVault saved = new SyncVault();
        VaultSaveStore.loadAsync(dir, VAULT_ID, saved).get(10, TimeUnit.SECONDS);
        assertEquals(Set.of("1,0"), saved.cells.keySet());
        vault.flushInFlight.set(true);
        VaultSaveStore.flushAsync(VAULT_ID, vault, dir);
        VaultSaveStore.awaitFlushDrain();
        assertEquals(0, vault.walPending);
        SyncVault later = new SyncVault();
        VaultSaveStore.loadAsync(dir, VAULT_ID, later).get(10, TimeUnit.SECONDS);
        assertEquals("rejected after boundary", later.cells.get("2,0").getRoomName());
    }

    private static final class BlockedWorker implements AutoCloseable {
        private final CountDownLatch releaseWorker = new CountDownLatch(1);
        private final Future<?> task;

        BlockedWorker() throws InterruptedException {
            this(VaultSaveStore.FLUSH_EXECUTOR);
        }

        BlockedWorker(java.util.concurrent.ExecutorService executor) throws InterruptedException {
            CountDownLatch ready = new CountDownLatch(1);
            task = executor.submit(() -> {
                ready.countDown();
                try {
                    if (!releaseWorker.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release the disk worker");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
        }

        void release() {
            releaseWorker.countDown();
        }

        @Override
        public void close() throws Exception {
            release();
            task.get(10, TimeUnit.SECONDS);
            VaultSaveStore.awaitFlushDrain();
        }
    }

    private static VaultCell cell(int x, String name) {
        return VaultCell.newBuilder().setX(x).setRoomName(name).build();
    }

    private static byte[] journal(VaultCell... cells) throws IOException {
        return journal(id(1), cells);
    }

    private static UUID id(long generation) {
        return new UUID(generation, 1234);
    }

    private static byte[] journal(UUID id, VaultCell... cells) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(VaultJournal.header(id));
        writeRecords(out, cells);
        return bytes.toByteArray();
    }

    private static byte[] legacyJournal(VaultCell... cells) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(VaultJournal.FILE_SIGNATURE);
        out.writeInt(1);
        writeRecords(out, cells);
        return bytes.toByteArray();
    }

    private static void writeRecords(DataOutputStream out, VaultCell... cells) throws IOException {
        for (VaultCell cell : cells) {
            byte[] payload = cell.toByteArray();
            CRC32 crc = new CRC32();
            crc.update(payload);
            out.writeInt(payload.length);
            out.writeInt((int) crc.getValue());
            out.write(payload);
        }
    }
}
