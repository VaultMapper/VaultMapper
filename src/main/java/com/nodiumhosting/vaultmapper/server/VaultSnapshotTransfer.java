package com.nodiumhosting.vaultmapper.server;

import com.nodiumhosting.vaultmapper.proto.Message;
import com.nodiumhosting.vaultmapper.proto.MessageType;
import com.nodiumhosting.vaultmapper.proto.Vault;
import com.nodiumhosting.vaultmapper.proto.VaultCell;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Prepare all size-safe packets off-thread, then deliver them in one pass. The
// server records the latest changes during preparation and sends those AFTER the
// base map, so concurrent updates cannot be rolled back by the older snapshot.
final class VaultSnapshotTransfer {
    static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "VaultMapper-Initial-Sync");
        thread.setDaemon(true);
        return thread;
    });
    private static final int PACKET_BUDGET = 256 * 1024;
    private static final int MAX_CHANGED_CELLS = 4096;
    final Map<String, byte[]> changes = new TreeMap<>(); // server thread only
    volatile boolean cancelled;

    void record(VaultCell cell, byte[] payload) {
        if (!changes.containsKey(SyncVault.cellKey(cell)) && changes.size() >= MAX_CHANGED_CELLS) {
            cancelled = true; // retry from a newer base, never silently omit updates
            return;
        }
        changes.put(SyncVault.cellKey(cell), payload);
    }

    record Prepared(List<byte[]> packets, int cellCount) {
    }

    Prepared prepare(Map<String, VaultCell> cells) {
        List<byte[]> packets = new ArrayList<>();
        Vault.Builder packet = Vault.newBuilder();
        int size = 0;
        int count = 0;
        for (VaultCell cell : cells.values()) {
            if (cancelled) return new Prepared(List.of(), 0);
            int cellSize = cell.getSerializedSize() + 8;
            if (size > 0 && size + cellSize > PACKET_BUDGET) {
                packets.add(encode(packet));
                packet = Vault.newBuilder();
                size = 0;
            }
            packet.addCells(cell);
            size += cellSize;
            count++;
        }
        if (packet.getCellsCount() > 0) packets.add(encode(packet));
        return new Prepared(List.copyOf(packets), count);
    }

    private static byte[] encode(Vault.Builder packet) {
        return Message.newBuilder().setType(MessageType.VAULT).setVault(packet).build().toByteArray();
    }
}
