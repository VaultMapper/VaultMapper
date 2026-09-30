package com.nodiumhosting.vaultmapper.network.sync;

import com.nodiumhosting.vaultmapper.map.VaultCell;
import com.nodiumhosting.vaultmapper.map.VaultMapOverlayRenderer;
import com.nodiumhosting.vaultmapper.map.VaultMap;
import com.nodiumhosting.vaultmapper.VaultMapper;
import com.nodiumhosting.vaultmapper.network.VaultMapperChannel;
import com.nodiumhosting.vaultmapper.network.packets.C2SJoinVaultPacket;
import com.nodiumhosting.vaultmapper.network.packets.C2SLeaveVaultPacket;
import com.nodiumhosting.vaultmapper.network.packets.C2SSyncPacket;
import com.nodiumhosting.vaultmapper.proto.Message;
import com.nodiumhosting.vaultmapper.proto.MessageType;
import com.nodiumhosting.vaultmapper.proto.VaultPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

// transport type used for when connection to mc server with server-side mod installed
@Mod.EventBusSubscriber(modid = VaultMapper.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ForgeSyncConnection implements ISyncConnection {
    private static final Map<String, ForgeUpdateOutbox> retainedOutboxes = new HashMap<>();
    private final String playerUUID;
    private final String vaultID;
    private final UUID sessionId = UUID.randomUUID();
    private final Consumer<Object> transport;
    private final ForgeUpdateOutbox outbox;
    private boolean closed = false;

    // cells are held back until the server's vault snapshot is complete, so a connecting
    // client can't overwrite the server's state with its own older state
    private boolean syncReady = false;
    private final Map<String, com.nodiumhosting.vaultmapper.proto.VaultCell> pendingCells = new HashMap<>();
    // cell keys seen across all snapshot chunks, applied when the end packet arrives
    private final Set<String> serverCellKeys = new HashSet<>();

    private boolean sentMove = false;
    private int oldX;
    private int oldZ;
    private float oldYaw;

    public ForgeSyncConnection(String playerUUID, String vaultID) {
        this(playerUUID, vaultID, ForgeSyncConnection::sendToServer, retainedOutbox(playerUUID, vaultID));
    }

    ForgeSyncConnection(String playerUUID, String vaultID, Consumer<Object> transport) {
        this(playerUUID, vaultID, transport, new ForgeUpdateOutbox());
    }

    ForgeSyncConnection(String playerUUID, String vaultID, Consumer<Object> transport, ForgeUpdateOutbox outbox) {
        this.playerUUID = playerUUID;
        this.vaultID = vaultID;
        this.transport = transport;
        this.outbox = outbox;
    }

    private static ForgeUpdateOutbox retainedOutbox(String playerUUID, String vaultID) {
        var server = Minecraft.getInstance().getCurrentServer();
        String key = (server == null ? "integrated" : server.ip) + ":" + playerUUID + ":" + vaultID;
        // Completed queues do not need to accumulate across every visited vault.
        // Outstanding batches are deliberately retained, never silently evicted.
        retainedOutboxes.entrySet().removeIf(entry -> !entry.getKey().equals(key) && entry.getValue().isEmpty());
        return retainedOutboxes.computeIfAbsent(key, ignored -> new ForgeUpdateOutbox());
    }

    public UUID sessionId() {
        return sessionId;
    }

    public boolean acceptsSession(UUID responseSessionId) {
        return !closed && sessionId.equals(responseSessionId);
    }

    // checks for if server has forge channel available for mod
    public static boolean isAvailable() {
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        return listener != null && VaultMapperChannel.CHANNEL.isRemotePresent(listener.getConnection());
    }

    @Override
    public void connect() {
        serverCellKeys.clear();
        transport.accept(new C2SJoinVaultPacket(vaultID, sessionId));
        VaultMapOverlayRenderer.syncErrorState = false;
    }

    @Override
    public void closeGracefully() {
        if (closed) return;
        closed = true;
        transport.accept(new C2SLeaveVaultPacket(vaultID, sessionId));
    }

    @Override
    public void sendCellPacket(VaultCell cell) {
        if (closed) return;
        if (!syncReady) {
            pendingCells.put(cell.x + "," + cell.z, SyncPayloadHandler.cellToPacket(cell));
            return;
        }
        outbox.queue(SyncPayloadHandler.cellToPacket(cell));
        tick(System.nanoTime());
    }

    // a snapshot chunk arrived - its cells are already merged into the map by
    // SyncPayloadHandler, here we only remember which cells the server already has
    public void onVaultChunkReceived(Set<String> chunkCellKeys) {
        if (!syncReady) serverCellKeys.addAll(chunkCellKeys);
    }

    // the server's vault snapshot is complete (end packet arrived) - the client may only
    // contribute cells the server doesn't know; server state always wins on conflicts
    public void onVaultStateReceived(UUID responseSessionId) {
        if (!acceptsSession(responseSessionId) || syncReady) return;
        syncReady = true;
        for (var cell : pendingCells.values()) {
            if (!serverCellKeys.contains(cell.getX() + "," + cell.getZ())) {
                outbox.queueCached(cell);
            }
        }
        pendingCells.clear();
        serverCellKeys.clear();
        outbox.sendDue(sessionId, transport, System.nanoTime(), true);
    }

    public void onUpdateAcknowledged(UUID responseSession, UUID source, long sequence) {
        if (acceptsSession(responseSession) && outbox.acknowledge(source, sequence)) tick(System.nanoTime());
    }

    public void onUpdateStreamReset(UUID responseSession, UUID oldSource, long sequence, UUID newSource) {
        if (acceptsSession(responseSession) && outbox.resetStream(oldSource, sequence, newSource)) {
            outbox.sendDue(sessionId, transport, System.nanoTime(), true);
        }
    }

    void tick(long now) {
        if (!closed && syncReady) outbox.sendDue(sessionId, transport, now, false);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && VaultMap.syncClient instanceof ForgeSyncConnection connection) {
            connection.tick(System.nanoTime());
        }
    }

    @Override
    public void sendMovePacket(String name, int cellX, int cellZ, float rotation) {
        if (closed) return;
        if (sentMove && oldX == cellX && oldZ == cellZ && oldYaw == rotation) {
            return;
        }
        sentMove = true;
        oldX = cellX;
        oldZ = cellZ;
        oldYaw = rotation;

        transport.accept(new C2SSyncPacket(sessionId, Message.newBuilder()
                .setType(MessageType.VAULT_PLAYER)
                .setVaultPlayer(VaultPlayer.newBuilder()
                        .setUuid(name)
                        .setX(cellX)
                        .setZ(cellZ)
                        .setYaw(rotation)
                        .setColor(SyncPayloadHandler.getSyncColor())
                        .build())
                .build()
                .toByteArray()));
    }

    private static void sendToServer(Object msg) {
        if (Minecraft.getInstance().getConnection() != null) {
            VaultMapperChannel.CHANNEL.sendToServer(msg);
        }
    }
}
