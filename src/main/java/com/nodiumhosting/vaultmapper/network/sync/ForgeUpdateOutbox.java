package com.nodiumhosting.vaultmapper.network.sync;

import com.nodiumhosting.vaultmapper.network.packets.C2SCellUpdatePacket;
import com.nodiumhosting.vaultmapper.proto.Vault;
import com.nodiumhosting.vaultmapper.proto.VaultCell;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

// Client thread. One immutable batch in flight; unsent edits are coalesced per
// room, not accumulated as an unbounded history. Reused when reconnecting so a
// server crash/lost acknowledgement does not discard the outstanding batch.
final class ForgeUpdateOutbox {
    private static final long RETRY_NANOS = 2_000_000_000L;
    UUID source = UUID.randomUUID();
    private final Map<String, VaultCell> waiting = new TreeMap<>();
    private long nextSequence = 1;
    private C2SCellUpdatePacket inFlight;
    private long lastSent;

    void queue(VaultCell cell) {
        waiting.put(key(cell), cell);
    }

    void queueCached(VaultCell cell) {
        waiting.putIfAbsent(key(cell), cell);
    }

    void sendDue(UUID session, Consumer<Object> transport, long now, boolean reconnect) {
        if (inFlight == null) {
            if (waiting.isEmpty()) return;
            List<VaultCell> batch = new ArrayList<>(128);
            int bytes = 0;
            var iterator = waiting.entrySet().iterator();
            while (iterator.hasNext() && batch.size() < 128) {
                VaultCell cell = iterator.next().getValue();
                int encodedSize = cell.getSerializedSize() + 8; // repeated-field tag and length
                if (bytes + encodedSize > C2SCellUpdatePacket.MAX_DATA_BYTES) break;
                batch.add(cell);
                bytes += encodedSize;
                iterator.remove();
            }
            if (batch.isEmpty()) return; // never send an oversized/invalid single cell
            inFlight = new C2SCellUpdatePacket(session, source, nextSequence++,
                    Vault.newBuilder().addAllCells(batch).build().toByteArray());
        } else if (!reconnect && now - lastSent < RETRY_NANOS) {
            return;
        }
        // A reconnect changes the transport token, never the batch identity/data.
        inFlight = new C2SCellUpdatePacket(session, source, inFlight.sequence(), inFlight.data());
        lastSent = now;
        transport.accept(inFlight);
    }

    boolean acknowledge(UUID responseSource, long sequence) {
        if (inFlight == null || !source.equals(responseSource) || inFlight.sequence() != sequence) return false;
        inFlight = null;
        return true;
    }

    boolean isEmpty() {
        return inFlight == null && waiting.isEmpty();
    }

    boolean resetStream(UUID oldSource, long oldSequence, UUID newSource) {
        if (inFlight == null || !source.equals(oldSource) || inFlight.sequence() != oldSequence) return false;
        // The server no longer has this stream (expired/reset vault). Preserve
        // the outstanding edits, but restart their identity at sequence one.
        source = newSource;
        nextSequence = 2;
        inFlight = new C2SCellUpdatePacket(inFlight.sessionId(), source, 1, inFlight.data());
        return true;
    }

    private static String key(VaultCell cell) {
        return cell.getX() + "," + cell.getZ();
    }
}
