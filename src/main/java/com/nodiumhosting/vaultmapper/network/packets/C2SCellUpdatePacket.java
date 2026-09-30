package com.nodiumhosting.vaultmapper.network.packets;

import com.nodiumhosting.vaultmapper.server.VaultSyncManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

// An immutable batch retried with the same source/sequence until acknowledged.
public record C2SCellUpdatePacket(UUID sessionId, UUID source, long sequence, byte[] data) {
    // Serverbound custom payloads allow only 32,767 bytes. Leave room for the
    // session/source/sequence, length prefix and Forge's message discriminator.
    public static final int MAX_DATA_BYTES = 32_000;
    public static void encode(C2SCellUpdatePacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.sessionId);
        buf.writeUUID(packet.source);
        buf.writeLong(packet.sequence);
        buf.writeByteArray(packet.data);
    }

    public static C2SCellUpdatePacket decode(FriendlyByteBuf buf) {
        return new C2SCellUpdatePacket(buf.readUUID(), buf.readUUID(), buf.readLong(), buf.readByteArray(MAX_DATA_BYTES));
    }

    public static void handle(C2SCellUpdatePacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> VaultSyncManager.handleCellUpdate(ctx.get().getSender(),
                packet.sessionId, packet.source, packet.sequence, packet.data));
        ctx.get().setPacketHandled(true);
    }
}
