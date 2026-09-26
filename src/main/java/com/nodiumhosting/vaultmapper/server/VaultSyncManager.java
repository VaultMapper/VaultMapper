package com.nodiumhosting.vaultmapper.server;

import com.nodiumhosting.vaultmapper.VaultMapper;
import com.nodiumhosting.vaultmapper.config.ServerConfig;
import com.nodiumhosting.vaultmapper.network.VaultMapperChannel;
import com.nodiumhosting.vaultmapper.network.packets.S2CSyncPacket;
import com.nodiumhosting.vaultmapper.network.packets.S2CVaultSyncEndPacket;
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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

// serverside sync server - subset of public sync server without web viewer and stats
//
// persistence: snapshot + WAL per vault in <world>/vaultmapper/
//   <id>.dat     main snapshot: int32 format version + serialized Vault proto
//   <id>.wal     active journal: int32 signature + int32 version, then records of
//                int32 length + int32 crc32 + serialized VaultCell (last write wins per cell)
//   <id>.wal.old journal rotated aside while an async flush folds it into the snapshot
//   <id>.tmp     staging for snapshot writes (moved into place atomically)
// every new cell is appended to the journal (one tiny write) so a crash mid-run doesn't
// forget the map; once enough records pile up a background thread rewrites the snapshot
@Mod.EventBusSubscriber(modid = VaultMapper.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class VaultSyncManager {
    private static final Pattern VAULT_ID_PATTERN = Pattern.compile("^vault_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    // vault snapshot format version, bump when the layout changes
    private static final int VAULT_FILE_VERSION = 1;
    // vault journal ("VaultMapper WAL") file signature + format version
    private static final int WAL_FILE_SIGNATURE = ('V' << 24) | ('M' << 16) | ('W' << 8) | 'A';
    private static final int WAL_VERSION = 1;

    private static final String SNAPSHOT_SUFFIX = ".dat";
    private static final String WAL_SUFFIX = ".wal";
    private static final String ROTATED_WAL_SUFFIX = ".wal.old";
    private static final String TMP_SUFFIX = ".tmp";
    // every file a vault save can consist of (order matters for extension stripping: longest first)
    private static final String[] SAVE_SUFFIXES = {ROTATED_WAL_SUFFIX, SNAPSHOT_SUFFIX, WAL_SUFFIX, TMP_SUFFIX};

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
    // ids of vaults with a save file on disk, built at startup - so joins don't touch the disk
    private static final Set<String> vaultFilesOnDisk = ConcurrentHashMap.newKeySet();

    private static volatile long lastHousekeepingMillis = 0;

    // flushes serialize whole vaults - kept off the server thread. deliberately never shut down:
    // integrated servers restart within the same JVM, and an abandoned flush is harmless anyway
    // (the journal on disk still holds every cell and recovery replays it)
    private static final ExecutorService FLUSH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "VaultMapper-WAL-Flush");
        thread.setDaemon(true);
        return thread;
    });

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
                    vault.cells.put(cell.getX() + "," + cell.getZ(), cell);
                    appendCell(vaultId, vault, cell);
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
                closeWalChannel(entry.getKey(), vault); // before its files are deleted
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
                long lastModified = lastSaveModifiedMillis(dir, vaultId);
                if (lastModified < 0) {
                    return true; // no files left - drop from the index
                }
                if (now - lastModified > retentionMs) {
                    VaultMapper.LOGGER.debug("Pruning vault sync save {}", vaultId);
                    deleteVaultFiles(dir, vaultId);
                    return true;
                }
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
                    String vaultId = stripSaveSuffix(name);
                    if (vaultId == null) {
                        return;
                    }
                    // vaults loaded in memory are governed by the in-memory sweep above
                    if (vaults.containsKey(vaultId)) {
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

    @Nullable
    private static Path getVaultDir() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return null;
        }
        return server.getWorldPath(LevelResource.ROOT).resolve("vaultmapper");
    }

    // appends one journal record for a cell - the only per-cell disk write, through a
    // channel held open for the vault's lifetime (no per-cell stat/open/close syscalls).
    // records are durable across process crashes without an explicit sync; power loss may
    // drop recent appends, which is accepted - snapshots (the records' final resting
    // place) are forced to disk before the journal holding them is released
    private static void appendCell(String vaultId, SyncVault vault, VaultCell cell) {
        long position = -1;
        try {
            FileChannel channel = vault.walChannel;
            if (channel == null) {
                channel = openWalChannel(vaultId, vault);
                if (channel == null) {
                    return;
                }
            }

            byte[] payload = cell.toByteArray();
            ByteBuffer record = ByteBuffer.allocate(8 + payload.length);
            record.putInt(payload.length);
            record.putInt(crc32(payload));
            record.put(payload);
            record.flip();

            position = channel.position();
            writeFully(channel, record);
            vault.walPending++; // a record actually hit the disk - count towards a flush
        } catch (Exception e) {
            // a write cut short leaves a partial record at the tail - chop it off so
            // later appends (and the next repair pass) continue from a clean journal
            truncateWalQuietly(vaultId, vault, position);
            VaultMapper.LOGGER.warn("Failed to append to vault sync journal {}: {}", vaultId, e.toString());
        }
    }

    // opens (and remembers) the journal channel of a vault, writing the file header when
    // the file is fresh or was truncated to empty by journal repair
    @Nullable
    private static FileChannel openWalChannel(String vaultId, SyncVault vault) throws IOException {
        Path dir = getVaultDir();
        if (dir == null) {
            return null;
        }
        Files.createDirectories(dir);
        Path file = dir.resolve(vaultId + WAL_SUFFIX);
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        long size = channel.size();
        if (size == 0) {
            ByteBuffer header = ByteBuffer.allocate(8);
            header.putInt(WAL_FILE_SIGNATURE);
            header.putInt(WAL_VERSION);
            header.flip();
            try {
                writeFully(channel, header);
            } catch (Exception e) {
                // a failed header write leaves a 1-7 byte torn header behind and the
                // channel unowned - empty the file and drop the handle so the next open
                // starts a clean journal instead of appending after the garbage
                try {
                    channel.truncate(0);
                } catch (Exception ignored) {
                }
                try {
                    channel.close();
                } catch (Exception ignored) {
                }
                throw e;
            }
        } else {
            channel.position(size); // sole writer - continue at the end
        }
        vaultFilesOnDisk.add(vaultId);
        vault.walChannel = channel;
        return channel;
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    // rolls the journal back to its size before a failed append
    private static void truncateWalQuietly(String vaultId, SyncVault vault, long size) {
        if (size < 0) {
            return;
        }
        try {
            FileChannel channel = vault.walChannel;
            if (channel != null && channel.isOpen()) {
                channel.truncate(size);
            }
        } catch (Exception e) {
            VaultMapper.LOGGER.warn("Failed to repair vault sync journal {} after a partial write: {}", vaultId, e.toString());
        }
    }

    // the channel must be closed before the journal is renamed or deleted - an open handle
    // would keep writing into the renamed file (and block renames entirely on Windows)
    private static void closeWalChannel(String vaultId, SyncVault vault) {
        FileChannel channel = vault.walChannel;
        vault.walChannel = null;
        if (channel != null) {
            try {
                channel.close();
            } catch (Exception e) {
                VaultMapper.LOGGER.warn("Failed to close vault sync journal {}: {}", vaultId, e.toString());
            }
        }
    }

    // prepares on the calling (server) thread: folds any stranded rotated journal back into
    // the live one, then rotates the journal aside; the expensive part (serializing the whole
    // vault and making it durable) happens on a background thread. cells arriving during the
    // flush land in a fresh journal - replay is idempotent, so snapshot overlap is harmless
    private static void flushVaultAsync(String vaultId, SyncVault vault) {
        // resolve the world and server NOW, while still on the server thread: if this server
        // stops and another world starts in the same JVM before the task runs, the snapshot
        // must land in the world it belongs to and must not touch the new server's index
        final Path dir;
        final MinecraftServer server;
        try {
            server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                vault.flushInFlight.set(false);
                return;
            }
            dir = server.getWorldPath(LevelResource.ROOT).resolve("vaultmapper");
            Files.createDirectories(dir);
            closeWalChannel(vaultId, vault); // the journal is about to be renamed
            reabsorbStrandedWal(vaultId, dir);
            Path wal = dir.resolve(vaultId + WAL_SUFFIX);
            if (Files.exists(wal)) {
                Files.move(wal, dir.resolve(vaultId + ROTATED_WAL_SUFFIX), StandardCopyOption.REPLACE_EXISTING);
            }
            vault.walPending = 0;
        } catch (Exception e) {
            vault.flushInFlight.set(false);
            markFlushFailed(vault);
            VaultMapper.LOGGER.warn("Failed to rotate vault sync journal {}: {}", vaultId, e.toString());
            return;
        }

        FLUSH_EXECUTOR.submit(() -> {
            try {
                // snapshot the live map - may include cells newer than the rotation, which is fine
                Vault protoVault = Vault.newBuilder().addAllCells(vault.cells.values()).build();

                // write to a temp file (so a crash mid-write can't corrupt the previous save)
                // and force it to disk BEFORE it replaces the snapshot - the rotated journal
                // may only be released after the snapshot is durable, or a power loss could
                // keep the journal deletion but lose the snapshot contents
                Path tmp = dir.resolve(vaultId + TMP_SUFFIX);
                try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    DataOutputStream out = new DataOutputStream(Channels.newOutputStream(channel));
                    out.writeInt(VAULT_FILE_VERSION);
                    out.write(protoVault.toByteArray());
                    out.flush();
                    channel.force(true);
                }
                Files.move(tmp, dir.resolve(vaultId + SNAPSHOT_SUFFIX), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                forceDirectory(dir);

                // only touch the shared index if this is still the same server instance
                if (ServerLifecycleHooks.getCurrentServer() == server) {
                    vaultFilesOnDisk.add(vaultId);
                }

                // snapshot durable - the rotated journal is fully absorbed
                Files.deleteIfExists(dir.resolve(vaultId + ROTATED_WAL_SUFFIX));
                vault.flushFailures = 0;
                vault.nextFlushAllowedMillis = 0;
                VaultMapper.LOGGER.debug("Flushed vault sync {} to disk ({} cells)", vaultId, protoVault.getCellsCount());
            } catch (Exception e) {
                markFlushFailed(vault);
                VaultMapper.LOGGER.warn("Failed to flush vault sync {}: {}", vaultId, e.toString());
                // the rotated journal stays behind, recovery replays it - nothing is lost
            } finally {
                vault.flushInFlight.set(false);
            }
        });
    }

    // spaces repeated flush attempts exponentially apart (2, 4, 8 ... capped at 60 minutes)
    // so a persistently failing disk doesn't spam the log every housekeeping cycle
    private static void markFlushFailed(SyncVault vault) {
        vault.flushFailures++;
        long backoffMinutes = Math.min(60, 1L << Math.min(vault.flushFailures, 6));
        vault.nextFlushAllowedMillis = System.currentTimeMillis() + backoffMinutes * 60_000L;
    }

    // folds a stranded rotated journal (left by a flush that died after rotation) back into
    // the live journal, so the upcoming rotation can't overwrite cells that exist nowhere
    // else on disk. byte-duplicate reabsorption is harmless - replay is idempotent
    private static void reabsorbStrandedWal(String vaultId, Path dir) throws IOException {
        Path stranded = dir.resolve(vaultId + ROTATED_WAL_SUFFIX);
        if (!Files.exists(stranded)) {
            return;
        }
        byte[] bytes = Files.readAllBytes(stranded);
        int goodEnd = bytes.length == 0 ? 8 : scanWalGoodEnd(bytes);
        if (goodEnd < 0) {
            // unreadable strand (also torn/foreign header) - nothing in it can be salvaged
            VaultMapper.LOGGER.warn("Discarding unreadable rotated vault sync journal of {}", vaultId);
        } else if (goodEnd > 8) {
            Path wal = dir.resolve(vaultId + WAL_SUFFIX);
            boolean hasHeader = Files.exists(wal) && Files.size(wal) > 0;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!hasHeader) {
                DataOutputStream header = new DataOutputStream(out);
                header.writeInt(WAL_FILE_SIGNATURE);
                header.writeInt(WAL_VERSION);
            }
            out.write(bytes, 8, goodEnd - 8); // records only, without the strand's header
            Files.write(wal, out.toByteArray(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        Files.deleteIfExists(stranded);
    }

    // best-effort: forces the directory so a rename (like the snapshot move) is itself durable
    private static void forceDirectory(Path dir) {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (Exception ignored) {
            // some filesystems can't force directories - the file contents are still safe
        }
    }

    // recovery: snapshot first, then replay any journaled cells on top (rotated journal first -
    // it's the older one) and queue a flush to fold everything back into a clean snapshot
    private static void loadVault(String vaultId, SyncVault vault) {
        if (!vaultFilesOnDisk.contains(vaultId)) {
            return; // no save on disk (per the startup index) - nothing to load
        }
        try {
            Path dir = getVaultDir();
            if (dir == null) {
                return;
            }
            Path snapshot = dir.resolve(vaultId + SNAPSHOT_SUFFIX);
            if (Files.exists(snapshot)) {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(snapshot)));
                int version = in.readInt();
                if (version != VAULT_FILE_VERSION) {
                    VaultMapper.LOGGER.warn("Ignoring vault sync {} with unknown file format version {}", vaultId, version);
                } else {
                    Vault protoVault = Vault.parseFrom(in);
                    protoVault.getCellsList().forEach(cell -> vault.cells.put(cell.getX() + "," + cell.getZ(), cell));
                }
            }

            int replayed = replayWal(dir.resolve(vaultId + ROTATED_WAL_SUFFIX), vaultId, vault);
            replayed += replayWal(dir.resolve(vaultId + WAL_SUFFIX), vaultId, vault);

            VaultMapper.LOGGER.info("Loaded vault sync {} from disk ({} cells, {} journaled)", vaultId, vault.cells.size(), replayed);

            if (replayed > 0) {
                // journaled data isn't in the snapshot (or the snapshot is gone entirely) -
                // have the next housekeeping run fold it in
                vault.walPending = WAL_FLUSH_THRESHOLD;
            }
        } catch (Exception e) {
            VaultMapper.LOGGER.warn("Failed to load vault sync {}: {}", vaultId, e.toString());
        }
    }

    // replays a journal into the vault map, then repairs the file if needed so live appends
    // always continue from a clean tail:
    //  - a torn/corrupt tail (crash mid-append) is truncated away at the last intact record
    //  - a file whose header is torn or from an unknown format is truncated to empty -
    //    this includes WAL_VERSION bumps, which therefore discard journaled cells not yet
    //    folded into the snapshot (bounded by WAL_FLUSH_THRESHOLD) and must be considered
    //    a breaking change
    private static int replayWal(Path file, String vaultId, SyncVault vault) {
        try {
            if (!Files.exists(file)) {
                return 0;
            }
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                return 0;
            }

            int goodEnd = scanWalGoodEnd(bytes);
            if (goodEnd < 0) {
                VaultMapper.LOGGER.warn("Discarding vault sync journal {} of {} with a torn or unknown header", file.getFileName(), vaultId);
                Files.write(file, new byte[0]);
                return 0;
            }

            // the scanned prefix is guaranteed intact - parse it into the map
            int replayed = 0;
            int consumedEnd = 8; // end of the parsed prefix, as an offset into bytes
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 8, goodEnd - 8));
            while (in.available() > 0) {
                int length = in.readInt();
                in.readInt(); // crc - already validated by the scan
                byte[] payload = new byte[length];
                in.readFully(payload);
                VaultCell cell;
                try {
                    cell = VaultCell.parseFrom(payload);
                } catch (Exception e) {
                    // bytes are intact but no longer parseable (format changed?) - truncate here
                    VaultMapper.LOGGER.warn("Vault sync journal {} of {} has an unreadable record - recovered {} cells", file.getFileName(), vaultId, replayed);
                    break;
                }
                vault.cells.put(cell.getX() + "," + cell.getZ(), cell);
                replayed++;
                consumedEnd = goodEnd - in.available();
            }

            if (consumedEnd < bytes.length) {
                VaultMapper.LOGGER.warn("Truncating {} broken bytes from vault sync journal {} of {} - recovered {} cells",
                        bytes.length - consumedEnd, file.getFileName(), vaultId, replayed);
                Files.write(file, Arrays.copyOf(bytes, consumedEnd));
            }
            return replayed;
        } catch (Exception e) {
            VaultMapper.LOGGER.warn("Failed to replay vault sync journal {} of {}: {}", file.getFileName(), vaultId, e.toString());
            return 0;
        }
    }

    // scans a journal buffer without building anything, returns the end offset of the last
    // intact record - 8 if only the header is intact, -1 if the header itself is torn or unknown
    private static int scanWalGoodEnd(byte[] bytes) {
        if (bytes.length < 8) {
            return -1;
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            int signature = in.readInt();
            int version = in.readInt();
            if (signature != WAL_FILE_SIGNATURE || version != WAL_VERSION) {
                return -1;
            }
            // only regarded as intact once the entire record (header + payload, crc-checked)
            // has been consumed - a torn record must not move the boundary past its header
            int goodEnd = 8;
            while (in.available() >= 8) {
                int recordStart = bytes.length - in.available();
                int length = in.readInt();
                int crc = in.readInt();
                // a real cell never serializes to zero bytes, so length 0 is corruption,
                // not a record (it would otherwise slip through with a matching crc of 0)
                if (length <= 0 || length > in.available()) {
                    break; // torn record
                }
                byte[] payload = new byte[length];
                in.readFully(payload);
                if (crc32(payload) != crc) {
                    break; // corrupt record
                }
                goodEnd = recordStart + 8 + length;
            }
            return goodEnd;
        } catch (Exception e) {
            return -1;
        }
    }

    private static int crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        return (int) crc.getValue();
    }

    private static void deleteVaultFile(String vaultId) {
        try {
            Path dir = getVaultDir();
            if (dir != null) {
                deleteVaultFiles(dir, vaultId);
            }
        } catch (Exception e) {
            VaultMapper.LOGGER.warn("Failed to delete vault sync save {}: {}", vaultId, e.toString());
        }
        vaultFilesOnDisk.remove(vaultId);
    }

    private static void deleteVaultFiles(Path dir, String vaultId) throws IOException {
        for (String suffix : SAVE_SUFFIXES) {
            Files.deleteIfExists(dir.resolve(vaultId + suffix));
        }
    }

    // newest modification time across all of a vault's save files, or -1 if none exist
    private static long lastSaveModifiedMillis(Path dir, String vaultId) {
        long latest = -1;
        for (String suffix : SAVE_SUFFIXES) {
            try {
                Path file = dir.resolve(vaultId + suffix);
                if (Files.exists(file)) {
                    latest = Math.max(latest, Files.getLastModifiedTime(file).toMillis());
                }
            } catch (Exception ignored) {
            }
        }
        return latest;
    }

    // maps a save file name back to its vault id, or null if it isn't one of ours
    @Nullable
    private static String stripSaveSuffix(String name) {
        for (String suffix : SAVE_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return null;
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
                        // staging files don't prove a usable save on their own - skip them
                        .map(name -> name.endsWith(TMP_SUFFIX) ? null : stripSaveSuffix(name))
                        .filter(Objects::nonNull)
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
                    flushVaultAsync(vaultId, vault);
                }
            });
            sweepStaleVaults();
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        // make sure no journal handle survives the stop, even if a flush below bails out
        vaults.forEach(VaultSyncManager::closeWalChannel);

        // final flush attempt for every vault: snapshots are built from memory, so this
        // also persists cells whose journal append failed earlier
        vaults.forEach((vaultId, vault) -> {
            if (vault.flushInFlight.compareAndSet(false, true)) {
                flushVaultAsync(vaultId, vault);
            }
        });

        // drain all pending flushes before the world (or JVM) goes away: the executor is
        // single-threaded, so waiting on a barrier task means everything queued before it
        // has finished. without this an integrated-server restart could watch an old task
        // still moving/deleting journal files while the new server is already reading them
        try {
            FLUSH_EXECUTOR.submit(() -> {
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            VaultMapper.LOGGER.warn("Timed out waiting for vault sync flushes to drain: {}", e.toString());
        }

        lastHousekeepingMillis = 0; // let a quickly restarted server housekeep right away
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
        // journal records written since the last flush rotation. mutated on the server
        // thread ONLY (packet handlers, join/load, housekeeping tick) - the plain ++
        // would silently lose counts if a writer ever moved off the server thread
        volatile int walPending = 0;
        // set while a flush owns the .wal.old slot
        final AtomicBoolean flushInFlight = new AtomicBoolean(false);
        // open journal channel for appends - server thread only, closed before the
        // journal is renamed or deleted
        volatile FileChannel walChannel = null;
        // consecutive flush failures, and when a flush is next allowed - repeated attempts
        // are spaced exponentially apart (see markFlushFailed)
        volatile int flushFailures = 0;
        volatile long nextFlushAllowedMillis = 0;
    }
}
