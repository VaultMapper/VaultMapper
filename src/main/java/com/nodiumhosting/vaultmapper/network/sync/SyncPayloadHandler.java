package com.nodiumhosting.vaultmapper.network.sync;

import com.nodiumhosting.vaultmapper.VaultMapper;
import com.nodiumhosting.vaultmapper.config.ClientConfig;
import com.nodiumhosting.vaultmapper.gui.ToastMessageManager;
import com.nodiumhosting.vaultmapper.map.VaultCell;
import com.nodiumhosting.vaultmapper.map.VaultMap;
import com.nodiumhosting.vaultmapper.proto.Color;
import com.nodiumhosting.vaultmapper.proto.Message;
import com.nodiumhosting.vaultmapper.util.Util;

import java.util.HashSet;
import java.util.Set;

// client side handler for incoming messages
// shared by both transport types
public class SyncPayloadHandler {
    public static void handle(Message msg) {
        switch (msg.getType()) {
            case VAULT -> {
                var data = msg.getVault();
                Set<String> receivedCellKeys = new HashSet<>();
                for (var cell : data.getCellsList()) {
                    VaultCell vaultCell = cellFromPacket(cell);

                    VaultMap.addOrReplaceCell(vaultCell);
                    receivedCellKeys.add(vaultCell.x + "," + vaultCell.z);
                }
                // snapshot chunk applied - the forge connection remembers these keys until
                // the end packet completes the initial sync and releases held-back cells
                if (VaultMap.syncClient instanceof ForgeSyncConnection syncConnection) {
                    syncConnection.onVaultChunkReceived(receivedCellKeys);
                }
            }
            case VAULT_PLAYER -> {
                var data = msg.getVaultPlayer();
                var uuid = data.getUuid();
                var color = data.getColor();
                var x = data.getX();
                var z = data.getZ();
                var yaw = data.getYaw();

                String red = Integer.toHexString(color.getR());
                String green = Integer.toHexString(color.getG());
                String blue = Integer.toHexString(color.getB());
                String paddedRed = red.length() == 1 ? "0" + red : red;
                String paddedGreen = green.length() == 1 ? "0" + green : green;
                String paddedBlue = blue.length() == 1 ? "0" + blue : blue;
                String hex = "#" + paddedRed + paddedGreen + paddedBlue;

                VaultMap.updatePlayerMapData(uuid, hex, x, z, yaw);
            }
            case VAULT_CELL -> {
                var data = msg.getVaultCell();
                VaultCell cell = cellFromPacket(data);

                VaultMap.addOrReplaceCell(cell);
            }
            case PLAYER_DISCONNECT -> {
                var data = msg.getPlayerDisconnect();

                VaultMap.removePlayerMapData(data.getUuid());
            }
            case TOAST -> {
                var data = msg.getToast();
                ToastMessageManager.displayToast(data.getMessage());
            }
            case VIEWER_CODE -> {
                var data = msg.getViewerCode();
                VaultMap.viewerCode = data.getCode();
            }
            default -> VaultMapper.LOGGER.info("Something weird with handleMessage");
        }
    }

    public static VaultCell cellFromPacket(com.nodiumhosting.vaultmapper.proto.VaultCell data) {
        var x = data.getX();
        var z = data.getZ();
        var cellType = data.getCellType();
        var roomType = data.getRoomType();

        var cell = new VaultCell(x, z, cellType, roomType);

        cell.roomName = data.getRoomName();
        cell.explored = data.getExplored();
        cell.inscripted = data.getInscribed();
        cell.marked = data.getMarked();

        return cell;
    }

    public static com.nodiumhosting.vaultmapper.proto.VaultCell cellToPacket(VaultCell cell) {
        return com.nodiumhosting.vaultmapper.proto.VaultCell.newBuilder()
                .setX(cell.x)
                .setZ(cell.z)
                .setCellType(cell.cellType)
                .setRoomType(cell.roomType)
                .setRoomName(cell.roomName)
                .setExplored(cell.explored)
                .setInscribed(cell.inscripted)
                .setMarked(cell.marked)
                .build();
    }

    public static Color getSyncColor() {
        String col = ClientConfig.SYNC_COLOR.get();
        if (col.equals("random")) {
            col = Util.RandomColor();
            ClientConfig.SYNC_COLOR.set(col);
        }
        int R = Integer.parseInt(col.substring(1, 3), 16);
        int G = Integer.parseInt(col.substring(3, 5), 16);
        int B = Integer.parseInt(col.substring(5, 7), 16);

        return Color.newBuilder().setR(R).setG(G).setB(B).build();
    }
}
