package com.nodiumhosting.vaultmapper.network.packets;

import com.nodiumhosting.vaultmapper.server.VaultSyncManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

// sent by client when entering a vault
public class C2SJoinVaultPacket {
    private final String vaultId;

    public C2SJoinVaultPacket(String vaultId) {
        this.vaultId = vaultId;
    }

    public static void encode(C2SJoinVaultPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.vaultId);
    }

    public static C2SJoinVaultPacket decode(FriendlyByteBuf buf) {
        return new C2SJoinVaultPacket(buf.readUtf());
    }

    public static void handle(C2SJoinVaultPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> VaultSyncManager.handleJoin(ctx.get().getSender(), msg.vaultId));
        ctx.get().setPacketHandled(true);
    }
}
