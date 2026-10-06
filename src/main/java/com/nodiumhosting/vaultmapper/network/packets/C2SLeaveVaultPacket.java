package com.nodiumhosting.vaultmapper.network.packets;

import com.nodiumhosting.vaultmapper.server.VaultSyncManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

// sent by the client when it leaves vault
public class C2SLeaveVaultPacket {
    private final String vaultId;

    public C2SLeaveVaultPacket(String vaultId) {
        this.vaultId = vaultId;
    }

    public static void encode(C2SLeaveVaultPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.vaultId);
    }

    public static C2SLeaveVaultPacket decode(FriendlyByteBuf buf) {
        return new C2SLeaveVaultPacket(buf.readUtf());
    }

    public static void handle(C2SLeaveVaultPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (ctx.get().getSender() != null) {
                VaultSyncManager.handleLeave(ctx.get().getSender().getUUID(), msg.vaultId);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
