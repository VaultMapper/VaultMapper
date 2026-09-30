package com.nodiumhosting.vaultmapper.server;

import com.nodiumhosting.vaultmapper.proto.Message;
import com.nodiumhosting.vaultmapper.proto.MessageType;
import com.nodiumhosting.vaultmapper.proto.VaultCell;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class VaultSnapshotTransferTest {
    @Test
    void largeMapIsPreparedOffThreadAsOneCompleteSizeSafeDelivery() throws Exception {
        SyncVault vault = new SyncVault();
        for (int i = 0; i < 20_000; i++) vault.putCell(cell(i, "room_" + i + "x".repeat(200)));
        VaultSnapshotTransfer transfer = new VaultSnapshotTransfer();
        var prepared = CompletableFuture.supplyAsync(() -> transfer.prepare(vault.cells)).get(10, TimeUnit.SECONDS);
        assertEquals(20_000, prepared.cellCount());
        assertTrue(prepared.packets().size() > 1); // only the existing transport size limit
        int count = 0;
        for (byte[] packet : prepared.packets()) {
            assertTrue(packet.length < 1_048_576);
            Message message = Message.parseFrom(packet);
            assertEquals(MessageType.VAULT, message.getType());
            count += message.getVault().getCellsCount();
        }
        assertEquals(20_000, count);
    }

    @Test
    void bufferedLatestChangesRepairTheOlderBaseWithoutLosingNewRooms() throws Exception {
        SyncVault vault = new SyncVault();
        vault.putCell(cell(1, "old"));
        VaultSnapshotTransfer transfer = new VaultSnapshotTransfer();
        var prepared = CompletableFuture.supplyAsync(() -> transfer.prepare(vault.cells)).get(10, TimeUnit.SECONDS);
        record(transfer, cell(1, "intermediate"));
        record(transfer, cell(1, "latest"));
        record(transfer, cell(2, "new room"));
        Map<String, VaultCell> delivered = new HashMap<>();
        for (byte[] packet : prepared.packets()) {
            Message.parseFrom(packet).getVault().getCellsList().forEach(c -> delivered.put(SyncVault.cellKey(c), c));
        }
        for (byte[] packet : transfer.changes.values()) {
            VaultCell change = Message.parseFrom(packet).getVaultCell();
            delivered.put(SyncVault.cellKey(change), change);
        }
        assertEquals(2, transfer.changes.size());
        assertEquals("latest", delivered.get("1,0").getRoomName());
        assertEquals("new room", delivered.get("2,0").getRoomName());
    }

    @Test
    void overflowingChangeBufferCancelsRatherThanDeliveringIncompleteState() {
        VaultSnapshotTransfer transfer = new VaultSnapshotTransfer();
        for (int i = 0; i <= 4096; i++) record(transfer, cell(i, "updated"));
        assertTrue(transfer.cancelled);
        assertEquals(4096, transfer.changes.size());
        assertTrue(transfer.prepare(Map.of("1,0", cell(1, "base"))).packets().isEmpty());
    }

    private static void record(VaultSnapshotTransfer transfer, VaultCell cell) {
        transfer.record(cell, Message.newBuilder().setType(MessageType.VAULT_CELL).setVaultCell(cell).build().toByteArray());
    }

    private static VaultCell cell(int x, String name) {
        return VaultCell.newBuilder().setX(x).setRoomName(name).build();
    }
}
