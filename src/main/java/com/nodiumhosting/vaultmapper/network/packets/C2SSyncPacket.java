package com.nodiumhosting.vaultmapper.network.packets;

import com.nodiumhosting.vaultmapper.server.VaultSyncManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;
import java.util.UUID;

// a serialized protobuf sync Message going client to server
public class C2SSyncPacket {
    private final byte[] data;
    private final UUID sessionId;

    public C2SSyncPacket(UUID sessionId, byte[] data) {
        this.sessionId = sessionId;
        this.data = data;
    }

    public static void encode(C2SSyncPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.sessionId);
        buf.writeByteArray(msg.data);
    }

    public static C2SSyncPacket decode(FriendlyByteBuf buf) {
        return new C2SSyncPacket(buf.readUUID(), buf.readByteArray());
    }

    public static void handle(C2SSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> VaultSyncManager.handlePayload(ctx.get().getSender(), msg.sessionId, msg.data));
        ctx.get().setPacketHandled(true);
    }
}
