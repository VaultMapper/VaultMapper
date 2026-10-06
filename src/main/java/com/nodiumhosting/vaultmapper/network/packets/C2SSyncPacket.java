package com.nodiumhosting.vaultmapper.network.packets;

import com.nodiumhosting.vaultmapper.server.VaultSyncManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

// a serialized protobuf sync Message going client to server
public class C2SSyncPacket {
    private final byte[] data;

    public C2SSyncPacket(byte[] data) {
        this.data = data;
    }

    public static void encode(C2SSyncPacket msg, FriendlyByteBuf buf) {
        buf.writeByteArray(msg.data);
    }

    public static C2SSyncPacket decode(FriendlyByteBuf buf) {
        return new C2SSyncPacket(buf.readByteArray());
    }

    public static void handle(C2SSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> VaultSyncManager.handlePayload(ctx.get().getSender(), msg.data));
        ctx.get().setPacketHandled(true);
    }
}
