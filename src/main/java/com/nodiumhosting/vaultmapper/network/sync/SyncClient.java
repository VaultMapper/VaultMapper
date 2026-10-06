package com.nodiumhosting.vaultmapper.network.sync;

import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.nodiumhosting.vaultmapper.VaultMapper;
import com.nodiumhosting.vaultmapper.config.ClientConfig;
import com.nodiumhosting.vaultmapper.map.VaultCell;
import com.nodiumhosting.vaultmapper.map.VaultMap;
import com.nodiumhosting.vaultmapper.map.VaultMapOverlayRenderer;
import com.nodiumhosting.vaultmapper.proto.Message;
import com.nodiumhosting.vaultmapper.proto.MessageType;
import com.nodiumhosting.vaultmapper.proto.VaultPlayer;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Timer;
import java.util.TimerTask;

public class SyncClient extends WebSocketClient implements ISyncConnection {
    private static final long TICK_MS = 2000;
    private static final long KEEPALIVE_MS = 10000;
    private static final long INITIAL_RETRY_MS = 2000;
    private static final long MAX_RETRY_MS = 60000;
    private static final long CONNECT_ATTEMPT_TIMEOUT_MS = 30000;

    private final Timer keepConnectedTimer = new Timer();
    private final SyncClient self;
    MovePacket old_data = new MovePacket("", "", 0, 0, 0);
    private volatile boolean keepMeOn = true; // written on the main thread in closeGracefully(), read on the timer thread

    // the fields below are only accessed on the timer thread
    private boolean wasOpen = false;
    private int retryAttempt = 0;
    private long nextRetryAt = 0;
    private long attemptStartedAt = System.currentTimeMillis();
    private long lastKeepalive = 0;

    public SyncClient(String playerUUID, String vaultID) {
        super(URI.create(ClientConfig.VMSYNC_SERVER.get() + "/?vaultID=" + vaultID + "&uuid=" + playerUUID));

        self = this;

        keepConnectedTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    if (!keepMeOn) return;
                    boolean open = self.isOpen();
                    if (open) {
                        if (!wasOpen) {
                            // just (re)connected - reset the backoff
                            retryAttempt = 0;
                            nextRetryAt = 0;
                        }
                        long now = System.currentTimeMillis();
                        if (now - lastKeepalive >= KEEPALIVE_MS) {
                            lastKeepalive = now;
                            sendKeepalive();
                        }
                    } else {
                        long now = System.currentTimeMillis();
                        if (self.isClosed() && now >= nextRetryAt) {
                            // previous attempt failed - retry with exponential backoff
                            retryConnection(now);
                        } else if (!self.isClosing() && now - attemptStartedAt > CONNECT_ATTEMPT_TIMEOUT_MS) {
                            // attempt in flight is stuck (e.g. server accepts TCP but never responds) - abort and retry
                            retryConnection(now);
                        }
                        // otherwise an attempt is still in flight - let it finish
                    }
                    wasOpen = open;
                } catch (Exception e) {
                    // keep the timer alive even if a tick fails
                    VaultMapper.LOGGER.error("Sync WS keep-alive/reconnect error: " + e);
                }
            }

            // timer thread only
            private void retryConnection(long now) {
                retryAttempt++;
                long delay = Math.min(MAX_RETRY_MS, INITIAL_RETRY_MS * (1L << Math.min(retryAttempt - 1, 10)));
                nextRetryAt = now + delay;
                attemptStartedAt = now;
                self.reconnect(); // non-blocking
            }
        }, TICK_MS, TICK_MS);
    }

    public void sendKeepalive() {
        if (this.isClosing() || this.isClosed()) {
//            VaultMapper.LOGGER.info("Can't send keep-alive, socket is closed.");
        }
        this.send("keep_me_alive");
        //this.sendPing();
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
//        VaultMapper.LOGGER.info("Sync WS Connected");
        keepMeOn = true;
        VaultMapOverlayRenderer.syncErrorState = false;
        // note: the retry backoff is reset on the timer thread when it next ticks (single-thread confinement)
    }

    @Override
    public void onMessage(ByteBuffer buf) {
        try {
            var msg = Message.parseFrom(buf);
            SyncPayloadHandler.handle(msg);
        } catch (Exception e) {
            VaultMapper.LOGGER.error("Sync WS Error: " + e);
        }
    }

    @Override
    public void onMessage(String message) {
//        VaultMapper.LOGGER.info(message); // log the json
        try {
            var dataCapsule = new GsonBuilder().create().fromJson(message, Capsule.class);
            if (dataCapsule.type.equals(String.valueOf(PacketType.MOVE.getValue()))) {
                MovePacket movePacket = new GsonBuilder().create().fromJson(dataCapsule.data, MovePacket.class);

                VaultMap.updatePlayerMapData(movePacket.uuid, movePacket.color, movePacket.x, movePacket.z, movePacket.yaw);
            } else if (dataCapsule.type.equals(String.valueOf(PacketType.CELL.getValue()))) {
                VaultCell cellPacket = new GsonBuilder().create().fromJson(dataCapsule.data, VaultCell.class); //have to change maybe

                VaultMap.addOrReplaceCell(cellPacket);
            } else if (dataCapsule.type.equals(String.valueOf(PacketType.LEAVE.getValue()))) {
                LeavePacket leavePacket = new GsonBuilder().create().fromJson(dataCapsule.data, LeavePacket.class);

                VaultMap.removePlayerMapData(leavePacket.uuid);
            }
        } catch (Exception e) {
            VaultMapper.LOGGER.error("Sync WS Error: " + e);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        VaultMapOverlayRenderer.syncErrorState = true;
//        Logger.getAnonymousLogger().info("closed");
//        Logger.getAnonymousLogger().info(String.valueOf(code));
//        Logger.getAnonymousLogger().info(reason);
//        Logger.getAnonymousLogger().info(String.valueOf(remote));
    }

    @Override
    public void onError(Exception ex) {
        VaultMapOverlayRenderer.syncErrorState = true;
        VaultMapper.LOGGER.error("Sync WS Error: " + ex.toString());
    }

    @Override
    public void closeGracefully() {
        keepMeOn = false;
        keepConnectedTimer.cancel();
        this.close();
    }

    @Override
    public void sendCellPacket(VaultCell cell) {
        if (this.isOpen()) {
            this.send(Message.newBuilder()
                    .setType(MessageType.VAULT_CELL)
                    .setVaultCell(SyncPayloadHandler.cellToPacket(cell))
                    .build()
                    .toByteArray());
        }
    }

    @Override
    public void sendMovePacket(String name, int cellX, int cellZ, float rotation) {
        if (this.isOpen()) {
            MovePacket data = new MovePacket(name, "", cellX, cellZ, rotation); // legacy, remove and reimplement optimalization
            if (!old_data.equals(data)) {
                old_data = data;

                this.send(Message.newBuilder()
                        .setType(MessageType.VAULT_PLAYER)
                        .setVaultPlayer(VaultPlayer.newBuilder()
                                .setUuid(name)
                                .setX(cellX)
                                .setZ(cellZ)
                                .setYaw(rotation)
                                .setColor(SyncPayloadHandler.getSyncColor())
                                .build())
                        .build()
                        .toByteArray()
                );
            }

        }
    }

    enum PacketType {
        @SerializedName("0") JOIN("0"), //unused on client side for now
        @SerializedName("1") LEAVE("1"), //S2C for removing player arrows
        @SerializedName("2") CELL("2"),
        @SerializedName("3") MOVE("3");

        private final String value;

        PacketType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    class Capsule {
        public String type;
        public String data;

        public Capsule(String type, String data) {
            this.type = type;
            this.data = data;
        }
    }

    class LeavePacket {
        public String uuid;
        public String color;
    }

    class MovePacket {
        public String uuid;
        public String color;
        public int x;
        public int z;
        public float yaw;

        public MovePacket(String uuid, String color, int x, int z, float yaw) {
            this.uuid = uuid;
            this.color = color;
            this.x = x;
            this.z = z;
            this.yaw = yaw;
        }

        public boolean equals(MovePacket data) {
            return data.uuid.equals(this.uuid) && data.color.equals(this.color) && data.x == this.x && data.z == this.z && data.yaw == this.yaw;
        }
    }
}
