package com.nodiumhosting.vaultmapper.server;

import com.nodiumhosting.vaultmapper.VaultMapper;
import com.nodiumhosting.vaultmapper.proto.Vault;
import com.nodiumhosting.vaultmapper.proto.VaultCell;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// owns the vault save files on disk: layout and paths, the on-disk index, background
// snapshot flushes, recovery reads and retention sweeps - nothing about live sync traffic
//
// files per vault in <world>/vaultmapper/:
//   <id>.dat     main snapshot: int32 format version + serialized Vault proto
//   <id>.wal     active journal (record format: see VaultJournal)
//   <id>.wal.old journal rotated aside while an async flush folds it into the snapshot
//   <id>.tmp     staging for snapshot writes (moved into place atomically)
// every new cell is appended to the journal (one tiny write) so a crash mid-run doesn't
// forget the map; once enough records pile up a background thread rewrites the snapshot
// from live memory, and recovery reads the snapshot first, then replays any journaled
// cells on top of it (rotated journal first - it's the older one)
final class VaultSaveStore {
    // vault snapshot format version, bump when the layout changes
    private static final int SNAPSHOT_FILE_VERSION = 1;

    private static final String SNAPSHOT_SUFFIX = ".dat";
    private static final String WAL_SUFFIX = ".wal";
    private static final String ROTATED_WAL_SUFFIX = ".wal.old";
    private static final String TMP_SUFFIX = ".tmp";
    // every file a vault save can consist of (order matters for extension stripping: longest first)
    private static final String[] SAVE_SUFFIXES = {ROTATED_WAL_SUFFIX, SNAPSHOT_SUFFIX, WAL_SUFFIX, TMP_SUFFIX};

    // ids of vaults with a save file on disk, built at startup - so joins don't touch the disk
    private static final Set<String> vaultFilesOnDisk = ConcurrentHashMap.newKeySet();

    // flushes serialize whole vaults - kept off the server thread. deliberately never shut down:
    // integrated servers restart within the same JVM, and an abandoned flush is harmless anyway
    // (the journal on disk still holds every cell and recovery replays it)
    private static final ExecutorService FLUSH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "VaultMapper-WAL-Flush");
        thread.setDaemon(true);
        return thread;
    });

    @Nullable
    static Path getVaultDir() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return null;
        }
        return vaultDir(server);
    }

    private static Path vaultDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("vaultmapper");
    }

    static Path snapshotPath(Path dir, String vaultId) {
        return dir.resolve(vaultId + SNAPSHOT_SUFFIX);
    }

    static Path walPath(Path dir, String vaultId) {
        return dir.resolve(vaultId + WAL_SUFFIX);
    }

    static Path rotatedWalPath(Path dir, String vaultId) {
        return dir.resolve(vaultId + ROTATED_WAL_SUFFIX);
    }

    static Path tmpPath(Path dir, String vaultId) {
        return dir.resolve(vaultId + TMP_SUFFIX);
    }

    static void clearIndex() {
        vaultFilesOnDisk.clear();
    }

    // appends one cell to the vault's journal, registering the vault as having a save
    // on disk only when the record actually landed - a broken disk must not pollute
    // the index (sweep would self-heal it, but no reason to put it there in the
    // first place)
    static void appendCell(String vaultId, SyncVault vault, VaultCell cell) {
        Path dir = getVaultDir();
        if (dir == null) {
            return;
        }
        vault.walFile = walPath(dir, vaultId);
        if (VaultJournal.appendCell(vaultId, vault, cell)) {
            vaultFilesOnDisk.add(vaultId);
        }
    }

    // closes the vault's journal and deletes its save files (in that order - an open
    // handle would keep writing into the deleted path and block deletion on Windows)
    static void deleteSave(String vaultId, SyncVault vault) {
        VaultJournal.closeChannel(vaultId, vault);
        deleteVaultFile(vaultId);
    }

    // makes sure no journal handle survives e.g. a server stop
    static void closeAllJournals(Map<String, SyncVault> vaults) {
        vaults.forEach(VaultJournal::closeChannel);
    }

    // (re)builds the index of vault save files present on disk
    static void rebuildIndex() {
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

    // prepares on the calling (server) thread: folds any stranded rotated journal back into
    // the live one, then rotates the journal aside; the expensive part (serializing the whole
    // vault and making it durable) happens on a background thread. cells arriving during the
    // flush land in a fresh journal - replay is idempotent, so snapshot overlap is harmless
    static void flushAsync(String vaultId, SyncVault vault) {
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
            dir = vaultDir(server);
            Files.createDirectories(dir);
            VaultJournal.closeChannel(vaultId, vault); // the journal is about to be renamed
            reabsorbStrandedWal(vaultId, dir);
            Path wal = walPath(dir, vaultId);
            if (Files.exists(wal)) {
                Files.move(wal, rotatedWalPath(dir, vaultId), StandardCopyOption.REPLACE_EXISTING);
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
                Path tmp = tmpPath(dir, vaultId);
                try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    DataOutputStream out = new DataOutputStream(Channels.newOutputStream(channel));
                    out.writeInt(SNAPSHOT_FILE_VERSION);
                    out.write(protoVault.toByteArray());
                    out.flush();
                    channel.force(true);
                }
                Files.move(tmp, snapshotPath(dir, vaultId), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                forceDirectory(dir);

                // only touch the shared index if this is still the same server instance
                if (ServerLifecycleHooks.getCurrentServer() == server) {
                    vaultFilesOnDisk.add(vaultId);
                }

                // snapshot durable - the rotated journal is fully absorbed
                Files.deleteIfExists(rotatedWalPath(dir, vaultId));
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

    // waits for all queued flushes to finish: the executor is single-threaded, so waiting
    // on a barrier task means everything queued before it has finished. without this an
    // integrated-server restart could watch an old task still moving/deleting journal
    // files while the new server is already reading them
    static void awaitFlushDrain() {
        try {
            FLUSH_EXECUTOR.submit(() -> {
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            VaultMapper.LOGGER.warn("Timed out waiting for vault sync flushes to drain: {}", e.toString());
        }
    }

    // folds a stranded rotated journal (left by a flush that died after rotation) back into
    // the live journal, so the upcoming rotation can't overwrite cells that exist nowhere
    // else on disk. byte-duplicate reabsorption is harmless - replay is idempotent
    static void reabsorbStrandedWal(String vaultId, Path dir) throws IOException {
        Path stranded = rotatedWalPath(dir, vaultId);
        if (!Files.exists(stranded)) {
            return;
        }
        byte[] bytes = Files.readAllBytes(stranded);
        int goodEnd = bytes.length == 0 ? VaultJournal.HEADER_BYTES : VaultJournal.scanGoodEnd(bytes);
        if (goodEnd < 0) {
            // unreadable strand (also torn/foreign header) - nothing in it can be salvaged
            VaultMapper.LOGGER.warn("Discarding unreadable rotated vault sync journal of {}", vaultId);
        } else if (goodEnd > VaultJournal.HEADER_BYTES) {
            Path wal = walPath(dir, vaultId);
            boolean hasHeader = Files.exists(wal) && Files.size(wal) > 0;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!hasHeader) {
                out.write(VaultJournal.header());
            }
            out.write(bytes, VaultJournal.HEADER_BYTES, goodEnd - VaultJournal.HEADER_BYTES); // records only, without the strand's header
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

    // recovery, returning the number of journaled cells applied: snapshot first, then replay
    // any journaled cells on top (rotated journal first - it's the older one)
    static int loadVault(String vaultId, SyncVault vault) {
        try {
            Path dir = getVaultDir();
            if (dir == null || !vaultFilesOnDisk.contains(vaultId)) {
                return 0; // no save on disk (per the startup index) - nothing to load
            }
            Path snapshot = snapshotPath(dir, vaultId);
            if (Files.exists(snapshot)) {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(snapshot)));
                int version = in.readInt();
                if (version != SNAPSHOT_FILE_VERSION) {
                    VaultMapper.LOGGER.warn("Ignoring vault sync {} with unknown file format version {}", vaultId, version);
                } else {
                    Vault protoVault = Vault.parseFrom(in);
                    protoVault.getCellsList().forEach(cell -> vault.cells.put(SyncVault.cellKey(cell), cell));
                }
            }

            int replayed = VaultJournal.replay(rotatedWalPath(dir, vaultId), vaultId, vault);
            replayed += VaultJournal.replay(walPath(dir, vaultId), vaultId, vault);

            VaultMapper.LOGGER.info("Loaded vault sync {} from disk ({} cells, {} journaled)", vaultId, vault.cells.size(), replayed);
            return replayed;
        } catch (Exception e) {
            VaultMapper.LOGGER.warn("Failed to load vault sync {}: {}", vaultId, e.toString());
            return 0;
        }
    }

    static void deleteVaultFile(String vaultId) {
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

    // full directory scan - prunes expired save files and leftovers, skipping live vaults
    static void sweepVaultFilesOnDisk(long now, long retentionMs, Set<String> liveVaultIds) {
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
                    // vaults loaded in memory are governed by the in-memory sweep instead
                    if (liveVaultIds.contains(vaultId)) {
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

    // prunes expired save files based on the on-disk vault index built at startup,
    // skipping live vaults - no directory scanning
    static void sweepIndexedVaultFiles(long now, long retentionMs, Set<String> liveVaultIds) {
        vaultFilesOnDisk.removeIf(vaultId -> {
            if (liveVaultIds.contains(vaultId)) {
                return false; // live vault, governed by the in-memory sweep instead
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
}
