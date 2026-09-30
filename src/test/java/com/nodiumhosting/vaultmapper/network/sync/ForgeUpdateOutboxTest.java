package com.nodiumhosting.vaultmapper.network.sync;

import com.nodiumhosting.vaultmapper.network.packets.C2SCellUpdatePacket;
import com.nodiumhosting.vaultmapper.proto.Vault;
import com.nodiumhosting.vaultmapper.proto.VaultCell;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ForgeUpdateOutboxTest {
    @Test
    void retriesImmutableBatchUntilItsExactAcknowledgementArrives() throws Exception {
        ForgeUpdateOutbox outbox = new ForgeUpdateOutbox();
        UUID session = UUID.randomUUID();
        List<Object> sent = new ArrayList<>();
        outbox.queue(cell(1, "first"));
        outbox.sendDue(session, sent::add, 0, false);
        C2SCellUpdatePacket first = (C2SCellUpdatePacket) sent.get(0);
        outbox.queue(cell(1, "newer"));
        outbox.queue(cell(1, "latest"));
        outbox.sendDue(session, sent::add, 1_000_000_000L, false);
        assertEquals(1, sent.size());
        outbox.sendDue(session, sent::add, 3_000_000_000L, false);
        C2SCellUpdatePacket retry = (C2SCellUpdatePacket) sent.get(1);
        assertEquals(first.source(), retry.source());
        assertEquals(first.sequence(), retry.sequence());
        assertArrayEquals(first.data(), retry.data());
        assertFalse(outbox.acknowledge(UUID.randomUUID(), first.sequence()));
        assertFalse(outbox.acknowledge(first.source(), first.sequence() + 1));
        assertTrue(outbox.acknowledge(first.source(), first.sequence()));
        outbox.sendDue(session, sent::add, 4_000_000_000L, false);
        C2SCellUpdatePacket next = (C2SCellUpdatePacket) sent.get(2);
        assertEquals(2, next.sequence());
        assertEquals("latest", Vault.parseFrom(next.data()).getCells(0).getRoomName());
    }

    @Test
    void largePendingMapUsesOneBoundedBatchInFlight() throws Exception {
        ForgeUpdateOutbox outbox = new ForgeUpdateOutbox();
        UUID session = UUID.randomUUID();
        List<Object> sent = new ArrayList<>();
        for (int i = 0; i < 1000; i++) outbox.queue(cell(i, "room"));
        int total = 0;
        long now = 0;
        while (!outbox.isEmpty()) {
            int previous = sent.size();
            outbox.sendDue(session, sent::add, now++, false);
            assertEquals(previous + 1, sent.size());
            C2SCellUpdatePacket packet = (C2SCellUpdatePacket) sent.get(previous);
            int count = Vault.parseFrom(packet.data()).getCellsCount();
            assertTrue(count > 0 && count <= 128);
            total += count;
            assertTrue(outbox.acknowledge(packet.source(), packet.sequence()));
        }
        assertEquals(1000, total);
        assertEquals(8, sent.size());
    }

    @Test
    void reconnectChangesOnlySessionTokenNotOutstandingUpdateIdentity() throws Exception {
        ForgeUpdateOutbox outbox = new ForgeUpdateOutbox();
        List<Object> sent = new ArrayList<>();
        ForgeSyncConnection first = new ForgeSyncConnection("player", "vault", sent::add, outbox);
        first.onVaultStateReceived(first.sessionId());
        first.sendCellPacket(new com.nodiumhosting.vaultmapper.map.VaultCell(1, 0,
                com.nodiumhosting.vaultmapper.proto.CellType.forNumber(0),
                com.nodiumhosting.vaultmapper.proto.RoomType.forNumber(0)));
        C2SCellUpdatePacket original = (C2SCellUpdatePacket) sent.get(0);
        first.closeGracefully();
        sent.clear();
        ForgeSyncConnection second = new ForgeSyncConnection("player", "vault", sent::add, outbox);
        second.onVaultChunkReceived(java.util.Set.of("1,0"));
        second.onVaultStateReceived(second.sessionId());
        C2SCellUpdatePacket retried = (C2SCellUpdatePacket) sent.get(0);
        assertEquals(second.sessionId(), retried.sessionId());
        assertEquals(original.source(), retried.source());
        assertEquals(original.sequence(), retried.sequence());
        assertArrayEquals(original.data(), retried.data());
        second.onUpdateAcknowledged(first.sessionId(), original.source(), original.sequence());
        assertFalse(outbox.isEmpty());
        second.onUpdateAcknowledged(second.sessionId(), original.source(), original.sequence());
        assertTrue(outbox.isEmpty());
    }

    private static VaultCell cell(int x, String name) {
        return VaultCell.newBuilder().setX(x).setRoomName(name).build();
    }

    @Test
    void maximumLengthUtf8RoomsRespectTheServerboundPayloadLimit() throws Exception {
        ForgeUpdateOutbox outbox = new ForgeUpdateOutbox();
        List<Object> sent = new ArrayList<>();
        UUID session = UUID.randomUUID();
        for (int i = 0; i < 128; i++) outbox.queue(cell(i, "\u0800".repeat(256)));
        int total = 0;
        while (!outbox.isEmpty()) {
            outbox.sendDue(session, sent::add, 0, false);
            C2SCellUpdatePacket packet = (C2SCellUpdatePacket) sent.get(sent.size() - 1);
            var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                buffer.writeVarInt(7); // Forge message discriminator
                C2SCellUpdatePacket.encode(packet, buffer);
                assertTrue(buffer.readableBytes() <= 32_767);
                assertTrue(packet.data().length <= C2SCellUpdatePacket.MAX_DATA_BYTES);
            } finally {
                buffer.release();
            }
            total += Vault.parseFrom(packet.data()).getCellsCount();
            outbox.acknowledge(packet.source(), packet.sequence());
        }
        assertEquals(128, total);
        assertTrue(sent.size() > 1);
    }

    @Test
    void expiredStreamRestartsWithoutDiscardingOutstandingOrWaitingEdits() throws Exception {
        ForgeUpdateOutbox outbox = new ForgeUpdateOutbox();
        UUID session = UUID.randomUUID();
        List<Object> sent = new ArrayList<>();
        outbox.queue(cell(1, "previously saved"));
        outbox.sendDue(session, sent::add, 0, false);
        C2SCellUpdatePacket first = (C2SCellUpdatePacket) sent.get(0);
        assertTrue(outbox.acknowledge(first.source(), 1));
        outbox.queue(cell(2, "outstanding"));
        outbox.sendDue(session, sent::add, 1, false);
        C2SCellUpdatePacket outstanding = (C2SCellUpdatePacket) sent.get(1);
        assertEquals(2, outstanding.sequence());
        outbox.queue(cell(3, "waiting"));
        UUID newSource = UUID.randomUUID();
        assertFalse(outbox.resetStream(UUID.randomUUID(), 2, newSource));
        assertTrue(outbox.resetStream(outstanding.source(), 2, newSource));
        outbox.sendDue(session, sent::add, 2, true);
        C2SCellUpdatePacket reset = (C2SCellUpdatePacket) sent.get(2);
        assertEquals(newSource, reset.source());
        assertEquals(1, reset.sequence());
        assertArrayEquals(outstanding.data(), reset.data());
        assertFalse(outbox.resetStream(outstanding.source(), 2, UUID.randomUUID()));
        assertTrue(outbox.acknowledge(newSource, 1));
        outbox.sendDue(session, sent::add, 3, false);
        C2SCellUpdatePacket next = (C2SCellUpdatePacket) sent.get(3);
        assertEquals(newSource, next.source());
        assertEquals(2, next.sequence());
        assertEquals("waiting", Vault.parseFrom(next.data()).getCells(0).getRoomName());
    }
}
