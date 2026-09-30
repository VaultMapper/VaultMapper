package com.nodiumhosting.vaultmapper.network.packets;

import com.nodiumhosting.vaultmapper.map.VaultMap;
import com.nodiumhosting.vaultmapper.network.sync.ForgeSyncConnection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

// A retained client stream outlived the server's vault/receipts. Restart its
// sequence under a new source without throwing away its outstanding batch.
public record S2CCellStreamResetPacket(UUID sessionId, UUID oldSource, long oldSequence, UUID newSource) {
    public static void encode(S2CCellStreamResetPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.sessionId);
        buf.writeUUID(packet.oldSource);
        buf.writeLong(packet.oldSequence);
        buf.writeUUID(packet.newSource);
    }

    public static S2CCellStreamResetPacket decode(FriendlyByteBuf buf) {
        return new S2CCellStreamResetPacket(buf.readUUID(), buf.readUUID(), buf.readLong(), buf.readUUID());
    }

    public static void handle(S2CCellStreamResetPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (VaultMap.syncClient instanceof ForgeSyncConnection connection) {
                connection.onUpdateStreamReset(packet.sessionId, packet.oldSource, packet.oldSequence, packet.newSource);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
