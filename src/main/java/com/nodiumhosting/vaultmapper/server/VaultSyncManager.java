package com.nodiumhosting.vaultmapper.server;

import com.nodiumhosting.vaultmapper.VaultMapper;
import com.nodiumhosting.vaultmapper.config.ServerConfig;
import com.nodiumhosting.vaultmapper.network.VaultMapperChannel;
import com.nodiumhosting.vaultmapper.network.packets.S2CSyncPacket;
import com.nodiumhosting.vaultmapper.network.packets.S2CVaultSyncEndPacket;
import com.nodiumhosting.vaultmapper.proto.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraftforge.network.PacketDistributor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

// serverside sync server - subset of public sync server without web viewer and stats
//
// owns the live sync sessions (who is in which vault, the merged map per vault, movement
// relays) and the housekeeping policy; everything that touches the disk lives in
// VaultJournal (journal record format) and VaultSaveStore (snapshots, index, sweeps) -
// see VaultJournal's and VaultSaveStore's class docs for the persistence layout
@Mod.EventBusSubscriber(modid = VaultMapper.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class VaultSyncManager {
    private static final Pattern VAULT_ID_PATTERN = Pattern.compile("^vault_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    // a vault's journal is folded into the snapshot once this many records are pending
    private static final int WAL_FLUSH_THRESHOLD = 100;
    // how often stale-vault sweeping and journal flushing are considered
    private static final long HOUSEKEEPING_INTERVAL_MS = 60_000;

    // sanity bounds on client-supplied data - journal files inherit whatever we accept
    private static final int MAX_SYNC_PAYLOAD_BYTES = 1_048_576;
    private static final int MAX_CELL_COORDINATE = 1_000_000;
    // legit room names are short resource locations - anything longer is a broken or malicious client
    private static final int MAX_ROOM_NAME_LENGTH = 256;

    // size budget per initial-sync snapshot chunk - keeps every chunk far below the vanilla
    // custom payload cap (1 MiB) no matter how large a vault grows
    private static final int INITIAL_SYNC_CHUNK_BYTES = 256 * 1024;

    private static final Map<String, SyncVault> vaults = new ConcurrentHashMap<>();
    private static final Map<UUID, String> playerVaults = new ConcurrentHashMap<>();

    private static volatile long lastHousekeepingMillis = 0;

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
            // restore saved progress after e.g. a server restart
            int journaled = VaultSaveStore.loadVault(vaultId, vault);
            if (journaled > 0) {
                // journaled data isn't in the snapshot (or the snapshot is gone entirely) -
                // have the next housekeeping run fold it in
                vault.walPending = WAL_FLUSH_THRESHOLD;
            }
        }
        vault.emptySince = 0;
        vault.players.add(uuid);
        playerVaults.put(uuid, vaultId);
        VaultMapper.LOGGER.debug("Player {} joined vault sync {}", uuid, vaultId);

        // send all known cells to the joining player (like the external sync server does on
        // connect), chunked so the initial sync can't exceed the vanilla custom payload cap
        sendVaultSnapshot(player, vault);
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
        if (data.length > MAX_SYNC_PAYLOAD_BYTES) {
            VaultMapper.LOGGER.warn("Dropping oversized sync payload of {} bytes from {}", data.length, player.getUUID());
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
                if (Math.abs(cell.getX()) > MAX_CELL_COORDINATE || Math.abs(cell.getZ()) > MAX_CELL_COORDINATE) {
                    VaultMapper.LOGGER.warn("Dropping out-of-bounds vault cell {},{} from {}", cell.getX(), cell.getZ(), uuid);
                    break;
                }
                if (cell.getRoomName().length() > MAX_ROOM_NAME_LENGTH) {
                    VaultMapper.LOGGER.warn("Dropping vault cell {},{} from {} with an oversized room name ({} chars)",
                            cell.getX(), cell.getZ(), uuid, cell.getRoomName().length());
                    break;
                }
                if (vault != null) {
                    vault.cells.put(SyncVault.cellKey(cell), cell);
                    VaultSaveStore.appendCell(vaultId, vault, cell);
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

    // sends the vault's cells as a series of VAULT snapshot chunks followed by an end packet
    // (S2CVaultSyncEndPacket) - chunked so a huge vault can't produce a packet above the
    // vanilla custom payload cap, which would disconnect the joining player
    private static void sendVaultSnapshot(ServerPlayer player, SyncVault vault) {
        Vault.Builder chunk = Vault.newBuilder();
        int chunkBytes = 0;
        int totalCells = 0;
        for (VaultCell cell : vault.cells.values()) {
            int cellBytes = cell.getSerializedSize();
            // flush the chunk before adding a cell that would overflow it, so an oversized
            // cell always lands alone in its own chunk (still well under the cap thanks to
            // the room name limit)
            if (chunkBytes > 0 && chunkBytes + cellBytes > INITIAL_SYNC_CHUNK_BYTES) {
                sendToPlayer(player, chunkMessage(chunk));
                chunk = Vault.newBuilder();
                chunkBytes = 0;
            }
            chunk.addCells(cell);
            chunkBytes += cellBytes;
            totalCells++;
        }
        if (chunk.getCellsCount() > 0) {
            sendToPlayer(player, chunkMessage(chunk));
        }
        VaultMapperChannel.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new S2CVaultSyncEndPacket(totalCells));
    }

    private static Message chunkMessage(Vault.Builder chunk) {
        return Message.newBuilder()
                .setType(MessageType.VAULT)
                .setVault(chunk.build())
                .build();
    }

    private static void sweepStaleVaults() {
        long now = System.currentTimeMillis();
        long retentionMs = Math.max(0, ServerConfig.EMPTY_VAULT_RETENTION_HOURS.get()) * 3_600_000L;
        vaults.entrySet().removeIf(entry -> {
            SyncVault vault = entry.getValue();
            // skip vaults mid-flush - wiping their files now would race the running write
            boolean stale = vault.players.isEmpty() && vault.emptySince > 0 && now - vault.emptySince > retentionMs
                    && !vault.flushInFlight.get();
            if (stale) {
                VaultMapper.LOGGER.debug("Dropping empty vault sync {}", entry.getKey());
                VaultSaveStore.deleteSave(entry.getKey(), vault);
            }
            return stale;
        });
        VaultSaveStore.sweepIndexedVaultFiles(now, retentionMs, vaults.keySet());
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
        VaultSaveStore.sweepVaultFilesOnDisk(now, retentionMs, vaults.keySet());
        VaultSaveStore.rebuildIndex();
    }

    // move flushes run every tick; journal flushes and stale sweeping once a minute
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        long intervalNanos = moveIntervalNanos();
        if (intervalNanos > 0) {
            long now = System.nanoTime();
            vaults.forEach((vaultId, vault) -> {
                if (now - vault.lastMoveRelayNanos >= intervalNanos) {
                    flushMoves(vaultId, vault);
                }
            });
        }

        long nowMillis = System.currentTimeMillis();
        if (nowMillis - lastHousekeepingMillis >= HOUSEKEEPING_INTERVAL_MS) {
            lastHousekeepingMillis = nowMillis;
            vaults.forEach((vaultId, vault) -> {
                if (vault.walPending >= WAL_FLUSH_THRESHOLD && nowMillis >= vault.nextFlushAllowedMillis
                        && vault.flushInFlight.compareAndSet(false, true)) {
                    VaultSaveStore.flushAsync(vaultId, vault);
                }
            });
            sweepStaleVaults();
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        // make sure no journal handle survives the stop, even if a flush below bails out
        VaultSaveStore.closeAllJournals(vaults);

        // final flush attempt for every vault: snapshots are built from memory, so this
        // also persists cells whose journal append failed earlier
        vaults.forEach((vaultId, vault) -> {
            if (vault.flushInFlight.compareAndSet(false, true)) {
                VaultSaveStore.flushAsync(vaultId, vault);
            }
        });

        // drain the pending flushes before the world (or JVM) goes away, so an
        // integrated-server restart can't watch an old task still writing files
        // while the new server is already reading them
        VaultSaveStore.awaitFlushDrain();

        lastHousekeepingMillis = 0; // let a quickly restarted server housekeep right away
        vaults.clear();
        playerVaults.clear();
        VaultSaveStore.clearIndex();
    }
}
