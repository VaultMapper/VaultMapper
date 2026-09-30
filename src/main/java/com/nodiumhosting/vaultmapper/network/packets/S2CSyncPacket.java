package com.nodiumhosting.vaultmapper.network.packets;

import com.nodiumhosting.vaultmapper.VaultMapper;
import com.nodiumhosting.vaultmapper.map.VaultMap;
import com.nodiumhosting.vaultmapper.network.sync.ForgeSyncConnection;
import com.nodiumhosting.vaultmapper.network.sync.SyncPayloadHandler;
import com.nodiumhosting.vaultmapper.proto.Message;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;
import java.util.UUID;

// a serialized protobuf sync Message going server to client
public class S2CSyncPacket {
    private final byte[] data;
    private final UUID sessionId;

    public S2CSyncPacket(UUID sessionId, byte[] data) {
        this.sessionId = sessionId;
        this.data = data;
    }

    public static void encode(S2CSyncPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.sessionId);
        buf.writeByteArray(msg.data);
    }

    public static S2CSyncPacket decode(FriendlyByteBuf buf) {
        return new S2CSyncPacket(buf.readUUID(), buf.readByteArray());
    }

    public static void handle(S2CSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // Check on the client thread, not receipt: it may switch vaults while
            // this work is queued. Old chunks must not modify the new map either.
            if (!(VaultMap.syncClient instanceof ForgeSyncConnection connection)
                    || !connection.acceptsSession(msg.sessionId)) {
                return;
            }
            try {
                SyncPayloadHandler.handle(Message.parseFrom(msg.data));
            } catch (Exception e) {
                VaultMapper.LOGGER.error("Failed to handle server sync packet: " + e);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
