package com.nodiumhosting.vaultmapper.network.sync;

import com.nodiumhosting.vaultmapper.map.VaultCell;
import com.nodiumhosting.vaultmapper.network.packets.C2SCellUpdatePacket;
import com.nodiumhosting.vaultmapper.proto.CellType;
import com.nodiumhosting.vaultmapper.proto.Vault;
import com.nodiumhosting.vaultmapper.proto.RoomType;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgeSyncConnectionTest {
    @Test
    void staleEndPacketCannotReleaseNewConnectionsPendingCells() throws Exception {
        List<Object> sent = new ArrayList<>();
        ForgeSyncConnection previous = new ForgeSyncConnection("player", "vault-a", sent::add);
        ForgeSyncConnection current = new ForgeSyncConnection("player", "vault-b", sent::add);
        current.sendCellPacket(cell(1));

        // A's queued end arrives after B became current, while B is still recovering.
        current.onVaultStateReceived(previous.sessionId());
        assertTrue(sent.isEmpty());
        current.sendCellPacket(cell(2));
        assertTrue(sent.isEmpty());

        current.onVaultStateReceived(current.sessionId());
        assertEquals(1, sent.size());
        assertCellPacket(sent.get(0), current.sessionId(), 1, 2);
        current.onVaultStateReceived(current.sessionId());
        assertEquals(1, sent.size()); // duplicate ends must not send pending cells twice
    }

    @Test
    void rejoiningSameVaultStillUsesANewSessionToken() {
        ForgeSyncConnection previous = new ForgeSyncConnection("player", "same-vault", packet -> {});
        List<Object> sent = new ArrayList<>();
        ForgeSyncConnection current = new ForgeSyncConnection("player", "same-vault", sent::add);
        current.sendCellPacket(cell(1));

        assertFalse(current.acceptsSession(previous.sessionId()));
        assertTrue(current.acceptsSession(current.sessionId()));
        current.onVaultStateReceived(previous.sessionId());
        assertTrue(sent.isEmpty());
        current.onVaultStateReceived(current.sessionId());
        assertEquals(1, sent.size());
    }

    @Test
    void closedConnectionRejectsDelayedResponsesAndCannotSendCells() {
        List<Object> sent = new ArrayList<>();
        ForgeSyncConnection connection = new ForgeSyncConnection("player", "vault", sent::add);
        connection.sendCellPacket(cell(1));
        connection.closeGracefully();
        int leavePacketCount = sent.size();

        assertFalse(connection.acceptsSession(connection.sessionId()));
        connection.onVaultStateReceived(connection.sessionId());
        connection.sendCellPacket(cell(2));
        assertEquals(leavePacketCount, sent.size());
    }

    @Test
    void matchingSnapshotStillSuppressesConflictingCachedCells() throws Exception {
        List<Object> sent = new ArrayList<>();
        ForgeSyncConnection connection = new ForgeSyncConnection("player", "vault", sent::add);
        connection.sendCellPacket(cell(1));
        connection.sendCellPacket(cell(2));
        connection.onVaultChunkReceived(Set.of("1,0"));
        connection.onVaultStateReceived(connection.sessionId());

        assertEquals(1, sent.size());
        assertCellPacket(sent.get(0), connection.sessionId(), 2);
    }

    @Test
    void delayedStreamResetCannotRewriteANewOrClosedConnectionsOutbox() {
        ForgeUpdateOutbox outbox = new ForgeUpdateOutbox();
        List<Object> sent = new ArrayList<>();
        ForgeSyncConnection old = new ForgeSyncConnection("player", "vault", sent::add, outbox);
        old.onVaultStateReceived(old.sessionId());
        old.sendCellPacket(cell(1));
        C2SCellUpdatePacket batch = (C2SCellUpdatePacket) sent.get(0);
        old.closeGracefully();
        sent.clear();
        ForgeSyncConnection current = new ForgeSyncConnection("player", "vault", sent::add, outbox);
        current.onVaultStateReceived(current.sessionId());
        UUID newSource = UUID.randomUUID();
        current.onUpdateStreamReset(old.sessionId(), batch.source(), batch.sequence(), newSource);
        old.onUpdateStreamReset(old.sessionId(), batch.source(), batch.sequence(), newSource);
        assertEquals(batch.source(), outbox.source);
        assertEquals(1, sent.size());
        current.onUpdateStreamReset(current.sessionId(), batch.source(), batch.sequence(), newSource);
        assertEquals(newSource, outbox.source);
        assertEquals(2, sent.size());
        current.onUpdateStreamReset(current.sessionId(), batch.source(), batch.sequence(), UUID.randomUUID());
        assertEquals(newSource, outbox.source);
        assertEquals(2, sent.size());
    }

    private static VaultCell cell(int x) {
        return new VaultCell(x, 0, CellType.forNumber(0), RoomType.forNumber(0));
    }

    private static void assertCellPacket(Object packet, UUID sessionId, Integer... x) throws Exception {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            C2SCellUpdatePacket.encode((C2SCellUpdatePacket) packet, buffer);
            assertEquals(sessionId, buffer.readUUID());
            buffer.readUUID();
            assertEquals(1, buffer.readLong());
            assertEquals(List.of(x), Vault.parseFrom(buffer.readByteArray()).getCellsList().stream().map(c -> c.getX()).toList());
        } finally {
            buffer.release();
        }
    }
}
