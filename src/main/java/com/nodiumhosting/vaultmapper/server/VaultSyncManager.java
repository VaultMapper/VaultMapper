package com.nodiumhosting.vaultmapper.server;

import com.nodiumhosting.vaultmapper.VaultMapper;
import com.nodiumhosting.vaultmapper.config.ServerConfig;
import com.nodiumhosting.vaultmapper.network.VaultMapperChannel;
import com.nodiumhosting.vaultmapper.network.packets.S2CSyncPacket;
import com.nodiumhosting.vaultmapper.proto.Message;
import com.nodiumhosting.vaultmapper.proto.MessageType;
import com.nodiumhosting.vaultmapper.proto.PlayerDisconnect;
import com.nodiumhosting.vaultmapper.proto.Vault;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

// serverside sync server - subset of public sync server without web viewer and stats
@Mod.EventBusSubscriber(modid = VaultMapper.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class VaultSyncManager {
    private static final Pattern VAULT_ID_PATTERN = Pattern.compile("^vault_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    // vault file format version, bump when the layout changes
    private static final int VAULT_FILE_VERSION = 1;

    private static final Map<String, SyncVault> vaults = new ConcurrentHashMap<>();
    private static final Map<UUID, String> playerVaults = new ConcurrentHashMap<>();

    public static void handleJoin(ServerPlayer player, String vaultId) {
        if (player == null || !VAULT_ID_PATTERN.matcher(vaultId).matches()) {
            return;
        }
        UUID uuid = player.getUUID();

        removePlayer(uuid); // leave any vault the player was previously in

        AtomicBoolean created = new AtomicBoolean(false);
        SyncVault vault = vaults.computeIfAbsent(vaultId, id -> {
            created.set(true);
            return new SyncVault();
        });
        if (created.get()) {
            loadVault(vaultId, vault); // restore saved progress after e.g. a server restart
        }
        vault.emptySince = 0;
        vault.players.add(uuid);
        playerVaults.put(uuid, vaultId);
        VaultMapper.LOGGER.debug("Player {} joined vault sync {}", uuid, vaultId);

        // send all known cells to the joining player (like the external sync server does on connect)
        Message msg = Message.newBuilder()
                .setType(MessageType.VAULT)
                .setVault(Vault.newBuilder().addAllCells(vault.cells.values()).build())
                .build();
        sendToPlayer(player, msg);

        sweepEmptyVaults();
    }

    public static void handleLeave(UUID playerUUID, String vaultId) {
        if (playerUUID == null || !vaultId.equals(playerVaults.get(playerUUID))) {
            return;
        }
        removePlayer(playerUUID);
    }

    public static void handlePayload(ServerPlayer player, byte[] data) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUUID();
        String vaultId = playerVaults.get(uuid);
        if (vaultId == null) {
            return; // not subscribed to any vault - ignore
        }

        Message msg;
        try {
            msg = Message.parseFrom(data);
        } catch (Exception e) {
            VaultMapper.LOGGER.warn("Failed to parse sync payload from {}: {}", uuid, e.toString());
            return;
        }

        SyncVault vault = vaults.get(vaultId);
        switch (msg.getType()) {
            case VAULT_PLAYER -> {
                if (vault != null && moveRateLimited(vault)) {
                    return;
                }
                // enforce the real player uuid to prevent spoofing other players' arrows
                Message relay = msg.toBuilder()
                        .setVaultPlayer(msg.getVaultPlayer().toBuilder().setUuid(uuid.toString()).build())
                        .build();
                broadcast(vaultId, uuid, relay);
            }
            case VAULT_CELL -> {
                var cell = msg.getVaultCell();
                if (vault != null) {
                    vault.cells.put(cell.getX() + "," + cell.getZ(), cell);
                    saveVault(vaultId, vault);
                }
                broadcast(vaultId, uuid, msg);
            }
            default -> VaultMapper.LOGGER.debug("Ignoring sync payload of type {} from {}", msg.getType(), uuid);
        }
    }

    // per vault rate limit on movement updates - paced evenly: at most one update per
    // (1000 / limit) ms passes, extra updates get dropped (next one replaces them anyway).
    // clients keep sending, so positions get sampled at the limit rate
    private static boolean moveRateLimited(SyncVault vault) {
        int maxPerSecond = ServerConfig.SYNC_RATE_LIMIT.get();
        if (maxPerSecond <= 0) {
            return false;
        }
        long intervalNanos = 1_000_000_000L / maxPerSecond;
        long now = System.nanoTime();
        if (now - vault.lastMoveRelayNanos < intervalNanos) {
            return true;
        }
        vault.lastMoveRelayNanos = now;
        return false;
    }

    // removes the player from whichever vault they are in, telling the remaining players
    private static void removePlayer(UUID playerUUID) {
        String vaultId = playerVaults.remove(playerUUID);
        if (vaultId == null) {
            return;
        }
        VaultMapper.LOGGER.debug("Player {} left vault sync {}", playerUUID, vaultId);

        SyncVault vault = vaults.get(vaultId);
        if (vault == null) {
            return;
        }
        vault.players.remove(playerUUID);

        Message msg = Message.newBuilder()
                .setType(MessageType.PLAYER_DISCONNECT)
                .setPlayerDisconnect(PlayerDisconnect.newBuilder().setUuid(playerUUID.toString()).build())
                .build();
        broadcast(vaultId, playerUUID, msg);

        if (vault.players.isEmpty()) {
            vault.emptySince = System.currentTimeMillis();
        }
    }

    private static void broadcast(String vaultId, UUID excludeUUID, Message msg) {
        SyncVault vault = vaults.get(vaultId);
        if (vault == null) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        S2CSyncPacket packet = new S2CSyncPacket(msg.toByteArray());
        for (UUID uuid : vault.players) {
            if (uuid.equals(excludeUUID)) {
                continue;
            }
            ServerPlayer target = server.getPlayerList().getPlayer(uuid);
            if (target != null) {
                VaultMapperChannel.CHANNEL.send(PacketDistributor.PLAYER.with(() -> target), packet);
            }
        }
    }

    private static void sendToPlayer(ServerPlayer player, Message msg) {
        VaultMapperChannel.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new S2CSyncPacket(msg.toByteArray()));
    }

    private static void sweepEmptyVaults() {
        long now = System.currentTimeMillis();
        long retentionMs = Math.max(0, ServerConfig.EMPTY_VAULT_RETENTION_HOURS.get()) * 3_600_000L;
        vaults.entrySet().removeIf(entry -> {
            SyncVault vault = entry.getValue();
            boolean stale = vault.players.isEmpty() && vault.emptySince > 0 && now - vault.emptySince > retentionMs;
            if (stale) {
                VaultMapper.LOGGER.debug("Dropping empty vault sync {}", entry.getKey());
                deleteVaultFile(entry.getKey());
            }
            return stale;
        });
    }

    // vaults are saved as a blob of: int32 format version + serialized Vault proto,
    // one file per vault in <world>/vaultmapper/, so a crash mid-run doesn't forget the map

    @Nullable
    private static Path getVaultDir() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return null;
        }
        return server.getWorldPath(LevelResource.ROOT).resolve("vaultmapper");
    }

    private static void saveVault(String vaultId, SyncVault vault) {
        try {
            Path dir = getVaultDir();
            if (dir == null) {
                return;
            }
            Files.createDirectories(dir);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(VAULT_FILE_VERSION);
            Vault protoVault = Vault.newBuilder().addAllCells(vault.cells.values()).build();
            out.write(protoVault.toByteArray());

            // write to a temp file first so a crash mid-write can't corrupt the previous save
            Path tmp = dir.resolve(vaultId + ".tmp");
            Files.write(tmp, bytes.toByteArray());
            Files.move(tmp, dir.resolve(vaultId + ".dat"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            VaultMapper.LOGGER.warn("Failed to save vault sync {}: {}", vaultId, e.toString());
        }
    }

    private static void loadVault(String vaultId, SyncVault vault) {
        try {
            Path dir = getVaultDir();
            if (dir == null) {
                return;
            }
            Path file = dir.resolve(vaultId + ".dat");
            if (!Files.exists(file)) {
                return;
            }
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(file)));
            int version = in.readInt();
            if (version != VAULT_FILE_VERSION) {
                VaultMapper.LOGGER.warn("Ignoring vault sync {} with unknown file format version {}", vaultId, version);
                return;
            }
            Vault protoVault = Vault.parseFrom(in);
            protoVault.getCellsList().forEach(cell -> vault.cells.put(cell.getX() + "," + cell.getZ(), cell));
            VaultMapper.LOGGER.info("Loaded vault sync {} from disk ({} cells)", vaultId, protoVault.getCellsCount());
        } catch (Exception e) {
            VaultMapper.LOGGER.warn("Failed to load vault sync {}: {}", vaultId, e.toString());
        }
    }

    private static void deleteVaultFile(String vaultId) {
        try {
            Path dir = getVaultDir();
            if (dir != null) {
                Files.deleteIfExists(dir.resolve(vaultId + ".dat"));
            }
        } catch (Exception e) {
            VaultMapper.LOGGER.warn("Failed to delete vault sync save {}: {}", vaultId, e.toString());
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        removePlayer(event.getPlayer().getUUID());
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        removePlayer(event.getPlayer().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        vaults.forEach(VaultSyncManager::saveVault);
        vaults.clear();
        playerVaults.clear();
    }

    private static class SyncVault {
        final Set<UUID> players = ConcurrentHashMap.newKeySet();
        final Map<String, com.nodiumhosting.vaultmapper.proto.VaultCell> cells = new ConcurrentHashMap<>();
        volatile long emptySince = 0;
        volatile long lastMoveRelayNanos = 0;
    }
}
