package com.nodiumhosting.vaultmapper.network.sync;

import com.nodiumhosting.vaultmapper.map.VaultCell;
import com.nodiumhosting.vaultmapper.map.VaultMapOverlayRenderer;
import com.nodiumhosting.vaultmapper.network.VaultMapperChannel;
import com.nodiumhosting.vaultmapper.network.packets.C2SJoinVaultPacket;
import com.nodiumhosting.vaultmapper.network.packets.C2SLeaveVaultPacket;
import com.nodiumhosting.vaultmapper.network.packets.C2SSyncPacket;
import com.nodiumhosting.vaultmapper.proto.Message;
import com.nodiumhosting.vaultmapper.proto.MessageType;
import com.nodiumhosting.vaultmapper.proto.VaultPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// transport type used for when connection to mc server with server-side mod installed
public class ForgeSyncConnection implements ISyncConnection {
    private final String playerUUID;
    private final String vaultID;
    private boolean closed = false;

    // cells are held back until the server sent its vault snapshot, so a connecting
    // client can't overwrite the server's state with its own older state
    private boolean syncReady = false;
    private final List<VaultCell> pendingCells = new ArrayList<>();

    private boolean sentMove = false;
    private int oldX;
    private int oldZ;
    private float oldYaw;

    public ForgeSyncConnection(String playerUUID, String vaultID) {
        this.playerUUID = playerUUID;
        this.vaultID = vaultID;
    }

    // checks for if server has forge channel available for mod
    public static boolean isAvailable() {
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        return listener != null && VaultMapperChannel.CHANNEL.isRemotePresent(listener.getConnection());
    }

    @Override
    public void connect() {
        sendToServer(new C2SJoinVaultPacket(vaultID));
        VaultMapOverlayRenderer.syncErrorState = false;
    }

    @Override
    public void closeGracefully() {
        if (closed) return;
        closed = true;
        sendToServer(new C2SLeaveVaultPacket(vaultID));
    }

    @Override
    public void sendCellPacket(VaultCell cell) {
        if (!syncReady) {
            pendingCells.add(cell);
            return;
        }
        sendCellNow(cell);
    }

    // when the server's vault snapshot arrives - the client may only contribute
    // cells the server doesn't know; server state always wins on conflicts
    public void onVaultStateReceived(Set<String> serverCellKeys) {
        syncReady = true;
        for (VaultCell cell : pendingCells) {
            if (!serverCellKeys.contains(cell.x + "," + cell.z)) {
                sendCellNow(cell);
            }
        }
        pendingCells.clear();
    }

    private void sendCellNow(VaultCell cell) {
        sendToServer(new C2SSyncPacket(Message.newBuilder()
                .setType(MessageType.VAULT_CELL)
                .setVaultCell(SyncPayloadHandler.cellToPacket(cell))
                .build()
                .toByteArray()));
    }

    @Override
    public void sendMovePacket(String name, int cellX, int cellZ, float rotation) {
        if (sentMove && oldX == cellX && oldZ == cellZ && oldYaw == rotation) {
            return;
        }
        sentMove = true;
        oldX = cellX;
        oldZ = cellZ;
        oldYaw = rotation;

        sendToServer(new C2SSyncPacket(Message.newBuilder()
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
