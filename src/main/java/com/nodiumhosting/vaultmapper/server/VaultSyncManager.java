package com.nodiumhosting.vaultmapper.server;

import com.nodiumhosting.vaultmapper.VaultMapper;
import com.nodiumhosting.vaultmapper.config.ServerConfig;
import com.nodiumhosting.vaultmapper.network.VaultMapperChannel;
import com.nodiumhosting.vaultmapper.network.packets.S2CSyncPacket;
import com.nodiumhosting.vaultmapper.network.packets.S2CVaultSyncEndPacket;
import com.nodiumhosting.vaultmapper.network.packets.S2CCellAckPacket;
import com.nodiumhosting.vaultmapper.network.packets.S2CCellStreamResetPacket;
import com.nodiumhosting.vaultmapper.network.packets.C2SCellUpdatePacket;
import com.google.protobuf.UnknownFieldSet;
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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
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

    private static final Map<String, SyncVault> vaults = new ConcurrentHashMap<>();
    private static final Map<UUID, String> playerVaults = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> playerSessions = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> playerSources = new ConcurrentHashMap<>();

    private static volatile long lastHousekeepingMillis = 0;

    public static void handleJoin(ServerPlayer player, String vaultId, UUID sessionId) {
        if (player == null || !VAULT_ID_PATTERN.matcher(vaultId).matches()) {
            return;
        }
        UUID uuid = player.getUUID();

        removePlayer(uuid); // leave any vault the player was previously in

        AtomicBoolean created = new AtomicBoolean(false);
        SyncVault vault = vaults.computeIfAbsent(vaultId, id -> {
            created.set(true);
            SyncVault session = new SyncVault();
            session.loaded = false;
            return session;
        });
        vault.emptySince = 0;
        vault.players.add(uuid);
        vault.awaitingSnapshot.add(uuid);
        playerVaults.put(uuid, vaultId);
        playerSessions.put(uuid, sessionId);
        VaultMapper.LOGGER.debug("Player {} joined vault sync {}", uuid, vaultId);

        if (created.get()) {
            startLoad(vaultId, vault, player.getServer());
        } else if (vault.loaded) {
            sendWaitingSnapshots(vaultId, vault, player.getServer());
        }
    }

    private static void startLoad(String vaultId, SyncVault vault, MinecraftServer server) {
        vault.loadInFlight = true;
        VaultSaveStore.loadAsync(vaultId, vault).whenComplete((journaled, failure) -> server.execute(() -> {
            // Do not revive an old session after expiry or a server restart.
            if (ServerLifecycleHooks.getCurrentServer() != server || vaults.get(vaultId) != vault) {
                return;
            }
            vault.loadInFlight = false;
            if (failure != null) {
                vault.nextLoadAllowedMillis = System.currentTimeMillis() + HOUSEKEEPING_INTERVAL_MS;
                VaultMapper.LOGGER.warn("Failed to recover vault sync {}: {}", vaultId, failure.toString());
                return;
            }
            vault.loaded = true;
            sendWaitingSnapshots(vaultId, vault, server);
        }));
    }

    private static void sendWaitingSnapshots(String vaultId, SyncVault vault, MinecraftServer server) {
        vault.awaitingSnapshot.forEach(uuid -> {
            if (!vaultId.equals(playerVaults.get(uuid))) {
                vault.awaitingSnapshot.remove(uuid);
                return;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null && !vault.initialSyncs.containsKey(uuid)) {
                sendVaultSnapshot(player, vaultId, vault);
            }
        });
    }

    public static void handleLeave(UUID playerUUID, String vaultId, UUID sessionId) {
        if (playerUUID == null || !vaultId.equals(playerVaults.get(playerUUID))
                || !sessionId.equals(playerSessions.get(playerUUID))) {
            return;
        }
        removePlayer(playerUUID);
    }

    public static void handlePayload(ServerPlayer player, UUID sessionId, byte[] data) {
        if (player == null) {
            return;
        }
        if (data.length > MAX_SYNC_PAYLOAD_BYTES) {
            VaultMapper.LOGGER.warn("Dropping oversized sync payload of {} bytes from {}", data.length, player.getUUID());
            return;
        }
        UUID uuid = player.getUUID();
        String vaultId = playerVaults.get(uuid);
        if (vaultId == null || !sessionId.equals(playerSessions.get(uuid))) {
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
            default -> VaultMapper.LOGGER.debug("Ignoring sync payload of type {} from {}", msg.getType(), uuid);
        }
    }

    public static void handleCellUpdate(ServerPlayer player, UUID session, UUID source, long sequence, byte[] data) {
        if (player == null || sequence <= 0 || data.length > C2SCellUpdatePacket.MAX_DATA_BYTES) return;
        UUID uuid = player.getUUID();
        String vaultId = playerVaults.get(uuid);
        if (vaultId == null || !session.equals(playerSessions.get(uuid))) return;
        UUID boundSource = playerSources.putIfAbsent(uuid, source);
        if (boundSource != null && !source.equals(boundSource)) return;
        SyncVault vault = vaults.get(vaultId);
        if (vault == null || !vault.loaded || vault.awaitingSnapshot.contains(uuid)) return;
        long committed = vault.durableReceipts.getOrDefault(source, 0L);
        if (sequence <= committed) {
            sendAck(player, session, source, sequence); // retry after a lost ack/restart
            return;
        }
        if (vault.pendingWrites.containsKey(source)) return;
        if (vault.needsStreamReset(source, sequence)) {
            UUID newSource = UUID.randomUUID();
            playerSources.put(uuid, newSource);
            VaultMapperChannel.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new S2CCellStreamResetPacket(session, source, sequence, newSource));
            return;
        }
        if (sequence != committed + 1 || !vault.acceptsSource(source)) return;
        final List<VaultCell> cells;
        try {
            Vault batch = Vault.parseFrom(data);
            if (batch.getCellsCount() == 0 || batch.getCellsCount() > 128) return;
            for (VaultCell cell : batch.getCellsList()) {
                if (Math.abs((long) cell.getX()) > MAX_CELL_COORDINATE || Math.abs((long) cell.getZ()) > MAX_CELL_COORDINATE
                        || cell.getRoomName().length() > MAX_ROOM_NAME_LENGTH) return;
            }
            cells = batch.getCellsList().stream().map(cell -> cell.toBuilder()
                    .setUnknownFields(UnknownFieldSet.getDefaultInstance()).build()).toList();
        } catch (Exception e) {
            return;
        }
        if (vault.acceptedSequences.getOrDefault(source, 0L) < sequence) {
            vault.acceptedSequences.put(source, sequence);
            cells.forEach(cell -> {
                vault.putCell(cell);
                broadcast(vaultId, uuid, Message.newBuilder().setType(MessageType.VAULT_CELL).setVaultCell(cell).build());
            });
        }
        MinecraftServer server = player.getServer();
        var write = VaultSaveStore.appendUpdate(vaultId, vault, source, sequence, cells, VaultSaveStore.getVaultDir());
        vault.pendingWrites.put(source, write);
        write.whenComplete((value, failure) -> server.execute(() -> {
            if (ServerLifecycleHooks.getCurrentServer() != server || vaults.get(vaultId) != vault) return;
            vault.pendingWrites.remove(source, write);
            if (failure == null && session.equals(playerSessions.get(uuid))) sendAck(player, session, source, sequence);
        }));
    }

    private static void sendAck(ServerPlayer player, UUID session, UUID source, long sequence) {
        VaultMapperChannel.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new S2CCellAckPacket(session, source, sequence));
    }

    // per vault rate limit on movement updates, paced evenly: updates go into a per player
    // queue (newest one always wins, nothing gets lost) and each due flush relays the
    // latest queued state of every player - so relayed positions get sampled at the limit rate
    private static void queueMoveRelay(String vaultId, SyncVault vault, UUID uuid, Message msg) {
        vault.latestMoves.put(uuid, msg);
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
        playerSessions.remove(playerUUID);
        playerSources.remove(playerUUID);
        if (vaultId == null) {
            return;
        }
        VaultMapper.LOGGER.debug("Player {} left vault sync {}", playerUUID, vaultId);

        SyncVault vault = vaults.get(vaultId);
        if (vault == null) {
            return;
        }
        vault.players.remove(playerUUID);
        vault.awaitingSnapshot.remove(playerUUID);
        VaultSnapshotTransfer transfer = vault.initialSyncs.remove(playerUUID);
        if (transfer != null) transfer.cancelled = true;
        vault.latestMoves.remove(playerUUID);
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
        byte[] data = msg.toByteArray();
        for (UUID uuid : vault.players) {
            if (uuid.equals(excludeUUID)) {
                continue;
            }
            if (vault.awaitingSnapshot.contains(uuid)) {
                VaultSnapshotTransfer transfer = vault.initialSyncs.get(uuid);
                if (transfer != null && msg.getType() == MessageType.VAULT_CELL) {
                    transfer.record(msg.getVaultCell(), data);
                }
                continue;
            }
            ServerPlayer target = server.getPlayerList().getPlayer(uuid);
            if (target != null) {
                VaultMapperChannel.CHANNEL.send(PacketDistributor.PLAYER.with(() -> target),
                        new S2CSyncPacket(playerSessions.get(uuid), data));
            }
        }
    }

    private static void sendToPlayer(ServerPlayer player, Message msg) {
        VaultMapperChannel.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new S2CSyncPacket(playerSessions.get(player.getUUID()), msg.toByteArray()));
    }

    private static void sendVaultSnapshot(ServerPlayer player, String vaultId, SyncVault vault) {
        MinecraftServer server = player.getServer();
        UUID uuid = player.getUUID();
        UUID session = playerSessions.get(uuid);
        VaultSnapshotTransfer transfer = new VaultSnapshotTransfer();
        vault.initialSyncs.put(uuid, transfer);
        VaultIoQueue.independent(VaultSnapshotTransfer.EXECUTOR, () -> transfer.prepare(vault.cells))
                .whenComplete((prepared, error) -> server.execute(() -> {
                    if (ServerLifecycleHooks.getCurrentServer() != server || vaults.get(vaultId) != vault
                            || !session.equals(playerSessions.get(uuid)) || vault.initialSyncs.get(uuid) != transfer) return;
                    vault.initialSyncs.remove(uuid);
                    if (error != null || transfer.cancelled) return; // retry, still awaiting a snapshot
                    // All packets are already serialized. Deliver the entire sync now,
                    // not paced across ticks; splitting only obeys Minecraft's size cap.
                    for (byte[] data : prepared.packets()) {
                        VaultMapperChannel.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new S2CSyncPacket(session, data));
                    }
                    for (byte[] data : transfer.changes.values()) {
                        VaultMapperChannel.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new S2CSyncPacket(session, data));
                    }
                    VaultMapperChannel.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                            new S2CVaultSyncEndPacket(session, prepared.cellCount()));
                    vault.awaitingSnapshot.remove(uuid);
                    vault.latestMoves.forEach((otherUUID, move) -> {
                        if (!otherUUID.equals(uuid)) sendToPlayer(player, move);
                    });
                }));
    }

    private static void sweepStaleVaults() {
        long now = System.currentTimeMillis();
        long retentionMs = Math.max(0, ServerConfig.EMPTY_VAULT_RETENTION_HOURS.get()) * 3_600_000L;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        vaults.forEach((id, vault) -> {
            // skip vaults mid-flush - wiping their files now would race the running write
            boolean stale = vault.players.isEmpty() && vault.emptySince > 0 && now - vault.emptySince > retentionMs
                    && vault.loaded && !vault.flushInFlight.get();
            if ((stale || vault.retiring) && !vault.deleteInFlight) {
                VaultMapper.LOGGER.debug("Dropping empty vault sync {}", id);
                retireVault(id, vault, () -> VaultSaveStore.deleteSave(id, vault, VaultSaveStore.getVaultDir()),
                        server::execute, () -> startLoad(id, vault, server));
            }
        });
        VaultSaveStore.sweepAsync(now, retentionMs, vaults.keySet());
    }

    static void retireVault(String id, SyncVault vault, Supplier<CompletableFuture<Void>> deletion,
                            Executor serverExecutor, Runnable reload) {
        vault.retiring = true;
        vault.deleteInFlight = true;
        vault.loaded = false;
        vault.recoveryComplete = false; // shutdown must not resurrect this expired map
        deletion.get().whenComplete((value, failure) -> serverExecutor.execute(() -> {
            if (vaults.get(id) != vault) return;
            vault.deleteInFlight = false;
            if (failure != null) {
                VaultMapper.LOGGER.warn("Failed retiring vault sync {} (will retry): {}", id, failure.toString());
            } else if (vault.players.isEmpty()) {
                vaults.remove(id, vault);
            } else {
                vault.retiring = false;
                reload.run(); // a join during cleanup waits for a fresh recovery
            }
        }));
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
        // Queue cleanup before the first recovery; neither blocks server ticks.
        VaultSaveStore.sweepAsync(now, retentionMs, vaults.keySet());
    }

    // move flushes run every tick; journal flushes and stale sweeping once a minute
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        vaults.forEach((id, vault) -> {
            if (vault.loaded && !vault.awaitingSnapshot.isEmpty()) {
                sendWaitingSnapshots(id, vault, ServerLifecycleHooks.getCurrentServer());
            }
        });
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
                if (!vault.loaded && !vault.retiring && !vault.loadInFlight && nowMillis >= vault.nextLoadAllowedMillis) {
                    startLoad(vaultId, vault, ServerLifecycleHooks.getCurrentServer());
                }
                if (vault.loaded && vault.walPending >= WAL_FLUSH_THRESHOLD && nowMillis >= vault.nextFlushAllowedMillis
                        && vault.flushInFlight.compareAndSet(false, true)) {
                    VaultSaveStore.flushAsync(vaultId, vault);
                }
            });
            sweepStaleVaults();
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        // Finish prior saves, then persist the latest memory state before clearing it.
        VaultSaveStore.flushAllAtShutdown(vaults, VaultSaveStore::flushAsync);

        lastHousekeepingMillis = 0; // let a quickly restarted server housekeep right away
        vaults.clear();
        playerVaults.clear();
        playerSessions.clear();
        playerSources.clear();
    }
}
