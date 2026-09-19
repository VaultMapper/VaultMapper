package com.nodiumhosting.vaultmapper.network.packets;

import com.nodiumhosting.vaultmapper.VaultMapper;
import com.nodiumhosting.vaultmapper.network.sync.SyncPayloadHandler;
import com.nodiumhosting.vaultmapper.proto.Message;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

// a serialized protobuf sync Message going server to client
public class S2CSyncPacket {
    private final byte[] data;

    public S2CSyncPacket(byte[] data) {
        this.data = data;
    }

    public static void encode(S2CSyncPacket msg, FriendlyByteBuf buf) {
        buf.writeByteArray(msg.data);
    }

    public static S2CSyncPacket decode(FriendlyByteBuf buf) {
        return new S2CSyncPacket(buf.readByteArray());
    }

    public static void handle(S2CSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                SyncPayloadHandler.handle(Message.parseFrom(msg.data));
            } catch (Exception e) {
                VaultMapper.LOGGER.error("Failed to handle server sync packet: " + e);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
