package com.nodiumhosting.vaultmapper.network.packets;

import com.nodiumhosting.vaultmapper.VaultMapper;
import com.nodiumhosting.vaultmapper.map.VaultMap;
import com.nodiumhosting.vaultmapper.network.sync.ForgeSyncConnection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;
import java.util.UUID;

// terminates the chunked initial vault sync - tells the client the server's snapshot is
// complete, so it may stop holding back its own locally cached cells
public class S2CVaultSyncEndPacket {
    private final int totalCells;
    private final UUID sessionId;

    public S2CVaultSyncEndPacket(UUID sessionId, int totalCells) {
        this.sessionId = sessionId;
        this.totalCells = totalCells;
    }

    public static void encode(S2CVaultSyncEndPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.sessionId);
        buf.writeVarInt(msg.totalCells);
    }

    public static S2CVaultSyncEndPacket decode(FriendlyByteBuf buf) {
        return new S2CVaultSyncEndPacket(buf.readUUID(), buf.readVarInt());
    }

    public static void handle(S2CVaultSyncEndPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            VaultMapper.LOGGER.debug("Vault sync snapshot complete ({} cells)", msg.totalCells);
            if (VaultMap.syncClient instanceof ForgeSyncConnection syncConnection) {
                syncConnection.onVaultStateReceived(msg.sessionId);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
