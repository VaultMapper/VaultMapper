package com.nodiumhosting.vaultmapper.server;

import com.nodiumhosting.vaultmapper.proto.VaultCell;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncVaultTest {
    @Test
    void workerSnapshotKeepsBoundaryValuesAndExcludesLaterCells() throws Exception {
        SyncVault vault = new SyncVault();
        vault.putCell(cell(1, "original"));
        vault.putCell(cell(2, "unchanged"));
        SyncVault.CellSnapshot snapshot = vault.beginCellSnapshot();

        // The worker may be queued behind another save while these updates arrive.
        vault.putCell(cell(1, "first update"));
        vault.putCell(cell(1, "last update"));
        vault.putCell(cell(3, "new cell"));
        vault.putCell(cell(3, "updated new cell"));

        Map<String, VaultCell> captured = byKey(CompletableFuture.supplyAsync(snapshot::copyCells).get(10, TimeUnit.SECONDS));

        assertEquals(2, captured.size());
        assertEquals("original", captured.get("1,0").getRoomName());
        assertEquals("unchanged", captured.get("2,0").getRoomName());
        assertFalse(captured.containsKey("3,0"));
        assertEquals("last update", vault.cells.get("1,0").getRoomName());
        assertEquals("updated new cell", vault.cells.get("3,0").getRoomName());
    }

    @Test
    void concurrentWorkerCopyDoesNotMixInLiveUpdates() throws Exception {
        SyncVault vault = new SyncVault();
        int cellCount = 20_000;
        for (int x = 0; x < cellCount; x++) {
            vault.putCell(cell(x, "original"));
        }
        SyncVault.CellSnapshot snapshot = vault.beginCellSnapshot();
        CountDownLatch workerReady = new CountDownLatch(1);
        CountDownLatch startCopy = new CountDownLatch(1);
        CompletableFuture<List<VaultCell>> copy = CompletableFuture.supplyAsync(() -> {
            workerReady.countDown();
            try {
                if (!startCopy.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to start snapshot copy");
                }
                return snapshot.copyCells();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });
        try {
            assertTrue(workerReady.await(10, TimeUnit.SECONDS));
            startCopy.countDown();
            for (int x = 0; x < cellCount; x++) {
                vault.putCell(cell(x, "updated"));
                vault.putCell(cell(cellCount + x, "added"));
            }

            Map<String, VaultCell> captured = byKey(copy.get(10, TimeUnit.SECONDS));
            assertEquals(cellCount, captured.size());
            for (int x = 0; x < cellCount; x++) {
                assertEquals("original", captured.get(x + ",0").getRoomName());
            }
            assertEquals(cellCount * 2, vault.cells.size());
        } finally {
            startCopy.countDown();
            snapshot.close();
        }
    }

    @Test
    void queuedWorkerHandlesManyDistinctChangesWithoutLosingBoundaryValues() throws Exception {
        SyncVault vault = new SyncVault();
        int cellCount = 20_000;
        for (int x = 0; x < cellCount; x++) {
            vault.putCell(cell(x, "original"));
        }
        SyncVault.CellSnapshot snapshot = vault.beginCellSnapshot();
        // A large earlier save can keep this worker queued while the change log grows.
        for (int x = 0; x < cellCount; x++) {
            vault.putCell(cell(x, "updated"));
        }

        List<VaultCell> captured = CompletableFuture.supplyAsync(snapshot::copyCells).get(10, TimeUnit.SECONDS);

        assertEquals(cellCount, captured.size());
        captured.forEach(cell -> assertEquals("original", cell.getRoomName()));
    }

    @Test
    void finishedOrCancelledCaptureAllowsAnotherSnapshot() throws Exception {
        SyncVault vault = new SyncVault();
        vault.putCell(cell(1, "first"));
        SyncVault.CellSnapshot cancelled = vault.beginCellSnapshot();
        assertThrows(IllegalStateException.class, vault::beginCellSnapshot);
        cancelled.close();

        SyncVault.CellSnapshot first = vault.beginCellSnapshot();
        Map<String, VaultCell> firstCells = byKey(CompletableFuture.supplyAsync(first::copyCells).get(10, TimeUnit.SECONDS));
        vault.putCell(cell(1, "second"));
        SyncVault.CellSnapshot second = vault.beginCellSnapshot();
        vault.putCell(cell(1, "third"));
        Map<String, VaultCell> secondCells = byKey(CompletableFuture.supplyAsync(second::copyCells).get(10, TimeUnit.SECONDS));

        assertEquals("first", firstCells.get("1,0").getRoomName());
        assertEquals("second", secondCells.get("1,0").getRoomName());
        assertEquals("third", vault.cells.get("1,0").getRoomName());
    }

    private static VaultCell cell(int x, String name) {
        return VaultCell.newBuilder().setX(x).setRoomName(name).build();
    }

    private static Map<String, VaultCell> byKey(List<VaultCell> cells) {
        Map<String, VaultCell> result = new HashMap<>();
        cells.forEach(cell -> result.put(SyncVault.cellKey(cell), cell));
        return result;
    }
}
