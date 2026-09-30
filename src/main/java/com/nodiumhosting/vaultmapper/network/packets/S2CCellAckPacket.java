package com.nodiumhosting.vaultmapper.network.packets;

import com.nodiumhosting.vaultmapper.map.VaultMap;
import com.nodiumhosting.vaultmapper.network.sync.ForgeSyncConnection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record S2CCellAckPacket(UUID sessionId, UUID source, long sequence) {
    public static void encode(S2CCellAckPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.sessionId);
        buf.writeUUID(packet.source);
        buf.writeLong(packet.sequence);
    }

    public static S2CCellAckPacket decode(FriendlyByteBuf buf) {
        return new S2CCellAckPacket(buf.readUUID(), buf.readUUID(), buf.readLong());
    }

    public static void handle(S2CCellAckPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (VaultMap.syncClient instanceof ForgeSyncConnection connection) {
                connection.onUpdateAcknowledged(packet.sessionId, packet.source, packet.sequence);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
