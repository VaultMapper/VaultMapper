package com.nodiumhosting.vaultmapper.network.packets;

import com.nodiumhosting.vaultmapper.server.VaultSyncManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;
import java.util.UUID;

// sent by the client when it leaves vault
public class C2SLeaveVaultPacket {
    private final String vaultId;
    private final UUID sessionId;

    public C2SLeaveVaultPacket(String vaultId, UUID sessionId) {
        this.vaultId = vaultId;
        this.sessionId = sessionId;
    }

    public static void encode(C2SLeaveVaultPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.vaultId);
        buf.writeUUID(msg.sessionId);
    }

    public static C2SLeaveVaultPacket decode(FriendlyByteBuf buf) {
        return new C2SLeaveVaultPacket(buf.readUtf(), buf.readUUID());
    }

    public static void handle(C2SLeaveVaultPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (ctx.get().getSender() != null) {
                VaultSyncManager.handleLeave(ctx.get().getSender().getUUID(), msg.vaultId, msg.sessionId);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
