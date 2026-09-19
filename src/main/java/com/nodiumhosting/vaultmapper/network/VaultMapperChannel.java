package com.nodiumhosting.vaultmapper.network;

import com.nodiumhosting.vaultmapper.VaultMapper;
import com.nodiumhosting.vaultmapper.network.packets.C2SJoinVaultPacket;
import com.nodiumhosting.vaultmapper.network.packets.C2SLeaveVaultPacket;
import com.nodiumhosting.vaultmapper.network.packets.C2SSyncPacket;
import com.nodiumhosting.vaultmapper.network.packets.S2CSyncPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

// channel for forge network transport type
// optional, mod falls back to public backend if needed
public class VaultMapperChannel {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(VaultMapper.MODID, "sync"),
            () -> PROTOCOL_VERSION,
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION),
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION)
    );

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, C2SJoinVaultPacket.class, C2SJoinVaultPacket::encode, C2SJoinVaultPacket::decode, C2SJoinVaultPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, C2SLeaveVaultPacket.class, C2SLeaveVaultPacket::encode, C2SLeaveVaultPacket::decode, C2SLeaveVaultPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, C2SSyncPacket.class, C2SSyncPacket::encode, C2SSyncPacket::decode, C2SSyncPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id, S2CSyncPacket.class, S2CSyncPacket::encode, S2CSyncPacket::decode, S2CSyncPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
}
