package com.nodiumhosting.vaultmapper.network.packets;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SyncPacketTest {
    @Test
    void sessionEnvelopesSurvivePacketCodecRoundTrips() {
        UUID session = UUID.randomUUID();
        String vault = "vault_00000000-0000-0000-0000-000000000001";
        byte[] payload = {1, 2, 3};
        checkRoundTrip(new C2SJoinVaultPacket(vault, session), C2SJoinVaultPacket::encode, C2SJoinVaultPacket::decode, b -> {
            assertEquals(vault, b.readUtf());
            assertEquals(session, b.readUUID());
        });
        UUID source = UUID.randomUUID();
        checkRoundTrip(new C2SCellUpdatePacket(session, source, 19, payload), C2SCellUpdatePacket::encode, C2SCellUpdatePacket::decode, b -> {
            assertEquals(session, b.readUUID());
            assertEquals(source, b.readUUID());
            assertEquals(19, b.readLong());
            assertArrayEquals(payload, b.readByteArray());
        });
        checkRoundTrip(new S2CCellAckPacket(session, source, 19), S2CCellAckPacket::encode, S2CCellAckPacket::decode, b -> {
            assertEquals(session, b.readUUID());
            assertEquals(source, b.readUUID());
            assertEquals(19, b.readLong());
        });
        UUID newSource = UUID.randomUUID();
        checkRoundTrip(new S2CCellStreamResetPacket(session, source, 19, newSource), S2CCellStreamResetPacket::encode, S2CCellStreamResetPacket::decode, b -> {
            assertEquals(session, b.readUUID());
            assertEquals(source, b.readUUID());
            assertEquals(19, b.readLong());
            assertEquals(newSource, b.readUUID());
        });
        checkRoundTrip(new C2SLeaveVaultPacket(vault, session), C2SLeaveVaultPacket::encode, C2SLeaveVaultPacket::decode, b -> {
            assertEquals(vault, b.readUtf());
            assertEquals(session, b.readUUID());
        });
        checkRoundTrip(new C2SSyncPacket(session, payload), C2SSyncPacket::encode, C2SSyncPacket::decode, b -> {
            assertEquals(session, b.readUUID());
            assertArrayEquals(payload, b.readByteArray());
        });
        checkRoundTrip(new S2CSyncPacket(session, payload), S2CSyncPacket::encode, S2CSyncPacket::decode, b -> {
            assertEquals(session, b.readUUID());
            assertArrayEquals(payload, b.readByteArray());
        });
        checkRoundTrip(new S2CVaultSyncEndPacket(session, 37), S2CVaultSyncEndPacket::encode, S2CVaultSyncEndPacket::decode, b -> {
            assertEquals(session, b.readUUID());
            assertEquals(37, b.readVarInt());
        });
    }

    private static <T> void checkRoundTrip(T packet, BiConsumer<T, FriendlyByteBuf> encode,
                                          Function<FriendlyByteBuf, T> decode,
                                          java.util.function.Consumer<FriendlyByteBuf> verify) {
        FriendlyByteBuf input = new FriendlyByteBuf(Unpooled.buffer());
        FriendlyByteBuf output = new FriendlyByteBuf(Unpooled.buffer());
        try {
            encode.accept(packet, input);
            T decoded = decode.apply(input);
            assertEquals(0, input.readableBytes());
            encode.accept(decoded, output);
            verify.accept(output);
            assertEquals(0, output.readableBytes());
        } finally {
            input.release();
            output.release();
        }
    }
}
