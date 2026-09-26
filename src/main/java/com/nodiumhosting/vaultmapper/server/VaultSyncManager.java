package com.nodiumhosting.vaultmapper.server;

import com.nodiumhosting.vaultmapper.VaultMapper;
import com.nodiumhosting.vaultmapper.config.ServerConfig;
import com.nodiumhosting.vaultmapper.network.VaultMapperChannel;
import com.nodiumhosting.vaultmapper.network.packets.S2CSyncPacket;
import com.nodiumhosting.vaultmapper.proto.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
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
    // ids of vaults with a save file on disk, built at startup - so joins don't touch the disk
    private static final Set<String> vaultFilesOnDisk = ConcurrentHashMap.newKeySet();

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

        sweepStaleVaults();
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
                // enforce the real player uuid to prevent spoofing other players' arrows
                Message relay = msg.toBuilder()
                        .setVaultPlayer(msg.getVaultPlayer().toBuilder().setUuid(uuid.toString()).build())
                        .build();
                if (vault != null) {
                    queueMoveRelay(vaultId, vault, uuid, relay);
                }
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

    // per vault rate limit on movement updates, paced evenly: updates go into a per player
    // queue (newest one always wins, nothing gets lost) and each due flush relays the
    // latest queued state of every player - so relayed positions get sampled at the limit rate
    private static void queueMoveRelay(String vaultId, SyncVault vault, UUID uuid, Message msg) {
        vault.pendingMoves.put(uuid, msg);

        long intervalNanos = moveIntervalNanos();
        if (intervalNanos <= 0 || System.nanoTime() - vault.lastMoveRelayNanos >= intervalNanos) {
            flushMoves(vaultId, vault);
        }
        // otherwise stays queued for the next due flush (chatty client) or the periodic tick flush
    }

    private static long moveIntervalNanos() {
        int maxPerSecond = ServerConfig.SYNC_RATE_LIMIT.get();
        return maxPerSecond <= 0 ? 0 : 1_000_000_000L / maxPerSecond;
    }

    private static void flushMoves(String vaultId, SyncVault vault) {
        if (vault.pendingMoves.isEmpty()) {
            return;
        }
        vault.lastMoveRelayNanos = System.nanoTime();
        vault.pendingMoves.forEach((uuid, msg) -> {
            // atomic remove so a queued update is relayed exactly once
            if (vault.pendingMoves.remove(uuid, msg)) {
                broadcast(vaultId, uuid, msg);
            }
        });
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
        vault.pendingMoves.remove(playerUUID); // don't let a queued position resurface after the disconnect broadcast

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

    private static void sweepStaleVaults() {
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
        sweepIndexedVaultFiles(now, retentionMs);
    }

    // prunes expired save files based on the on-disk vault index built at startup,
    // skipping vaults currently loaded in memory - no directory scanning
    private static void sweepIndexedVaultFiles(long now, long retentionMs) {
        vaultFilesOnDisk.removeIf(vaultId -> {
            if (vaults.containsKey(vaultId)) {
                return false; // live vault, governed by the in-memory sweep above
            }
            try {
                Path dir = getVaultDir();
                if (dir == null) {
                    return false;
                }
                Path file = dir.resolve(vaultId + ".dat");
                if (now - Files.getLastModifiedTime(file).toMillis() > retentionMs) {
                    VaultMapper.LOGGER.debug("Pruning vault sync save {}", vaultId);
                    Files.deleteIfExists(file);
                    return true;
                }
            } catch (java.nio.file.NoSuchFileException e) {
                return true; // already gone - drop from the index
            } catch (Exception e) {
                VaultMapper.LOGGER.warn("Failed to prune vault sync save {}: {}", vaultId, e.toString());
            }
            return false;
        });
    }

    // full directory scan - prunes expired save files and leftovers
    private static void sweepVaultFilesOnDisk(long now, long retentionMs) {
        try {
            Path dir = getVaultDir();
            if (dir == null || !Files.isDirectory(dir)) {
                return;
            }
            try (var files = Files.list(dir)) {
                files.forEach(file -> {
                    String name = file.getFileName().toString();
                    boolean isTemp = name.endsWith(".tmp");
                    boolean isVault = name.endsWith(".dat");
                    if (!isTemp && !isVault) {
                        return;
                    }
                    // vaults loaded in memory are governed by the in-memory sweep above
                    if (isVault && vaults.containsKey(name.substring(0, name.length() - ".dat".length()))) {
                        return;
                    }
                    try {
                        if (now - Files.getLastModifiedTime(file).toMillis() > retentionMs) {
                            VaultMapper.LOGGER.debug("Pruning vault sync save {}", name);
                            Files.deleteIfExists(file);
                        }
                    } catch (Exception e) {
                        VaultMapper.LOGGER.warn("Failed to prune vault sync save {}: {}", name, e.toString());
                    }
                });
            }
        } catch (Exception e) {
            VaultMapper.LOGGER.warn("Failed to sweep vault sync saves: {}", e.toString());
        }
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
            vaultFilesOnDisk.add(vaultId);
        } catch (Exception e) {
            VaultMapper.LOGGER.warn("Failed to save vault sync {}: {}", vaultId, e.toString());
        }
    }

    private static void loadVault(String vaultId, SyncVault vault) {
        if (!vaultFilesOnDisk.contains(vaultId)) {
            return; // no save on disk (per the startup index) - nothing to load
        }
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
        vaultFilesOnDisk.remove(vaultId);
    }

    // (re)builds the index of vault save files present on disk
    private static void rebuildVaultFileIndex() {
        vaultFilesOnDisk.clear();
        try {
            Path dir = getVaultDir();
            if (dir == null || !Files.isDirectory(dir)) {
                return;
            }
            try (var files = Files.list(dir)) {
                files.map(file -> file.getFileName().toString())
                        .filter(name -> name.endsWith(".dat"))
                        .map(name -> name.substring(0, name.length() - ".dat".length()))
                        .forEach(vaultFilesOnDisk::add);
            }
        } catch (Exception e) {
            VaultMapper.LOGGER.warn("Failed to index vault sync saves: {}", e.toString());
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
    public static void onServerStarted(ServerStartedEvent event) {
        long now = System.currentTimeMillis();
        long retentionMs = Math.max(0, ServerConfig.EMPTY_VAULT_RETENTION_HOURS.get()) * 3_600_000L;
        // prune expired save files left over from previous runs, then index what's left
        sweepVaultFilesOnDisk(now, retentionMs);
        rebuildVaultFileIndex();
    }

    // flushes queued movement updates when due - runs on the server thread like everything else here
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        long intervalNanos = moveIntervalNanos();
        if (intervalNanos <= 0) {
            return; // limit disabled - updates get flushed upon arrival
        }
        long now = System.nanoTime();
        vaults.forEach((vaultId, vault) -> {
            if (now - vault.lastMoveRelayNanos >= intervalNanos) {
                flushMoves(vaultId, vault);
            }
        });
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        vaults.forEach(VaultSyncManager::saveVault);
        vaults.clear();
        playerVaults.clear();
        vaultFilesOnDisk.clear();
    }

    private static class SyncVault {
        final Set<UUID> players = ConcurrentHashMap.newKeySet();
        final Map<String, VaultCell> cells = new ConcurrentHashMap<>();
        final Map<UUID, Message> pendingMoves = new ConcurrentHashMap<>();
        volatile long emptySince = 0;
        volatile long lastMoveRelayNanos = 0;
    }
}
