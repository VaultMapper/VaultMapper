package com.nodiumhosting.vaultmapper.map.snapshots;

import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import com.nodiumhosting.vaultmapper.VaultMapper;
import com.nodiumhosting.vaultmapper.config.ClientConfig;
import com.nodiumhosting.vaultmapper.gui.screen.VaultMapScreen;
import com.nodiumhosting.vaultmapper.map.VaultCell;
import com.nodiumhosting.vaultmapper.map.VaultMap;
import com.nodiumhosting.vaultmapper.util.Util;
import net.minecraft.client.Minecraft;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class MapSnapshot {
    public static final String mapSaveFolder= "vaultmaps/standard/";

    public static final String favoriteMapsFolder = "vaultmaps/favorite/";

    public static boolean checkedDirs = false;

    public static MapSnapshot lastSnapshotCache;

    public static void onVaultExit(UUID vaultUUID) {
        if (lastSnapshotCache == null) {
            return;
        }
        MapSnapshot.addMap(vaultUUID,lastSnapshotCache);
    }

    public static void toggleFavorite(UUID uuid) {
        makeSureFoldersExist();
        String mapPath = mapSaveFolder + uuid + ".vaultmap";
        String favPath = favoriteMapsFolder + uuid + ".vaultmap";
        File favorite = new File(favPath);
        if (favorite.exists()) {
            favorite.delete();
            return;
        }
        File map = new File(mapPath);
        if (!map.exists()) {
            return;
        }
        try {
            Files.copy(map.toPath(), favorite.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException e) {
            VaultMapper.LOGGER.error("Couldn't copy map to favorite");
        }
    }


    public static MapSnapshot takeSnapshot() {
        ArrayList<VaultCell> cells = new ArrayList<>(VaultMap.getCells());
        return new MapSnapshot(cells);
    }

    public static void addMap(UUID uuid, MapSnapshot snapshot) {
        makeSureFoldersExist();
        try {
//            FileWriter writer = new FileWriter(mapSaveFolder + uuid.toString() + ".vaultmap");
//            gson.toJson(snapshot, writer);
//            writer.close();

            FileOutputStream fos = new FileOutputStream(mapSaveFolder + uuid.toString() + ".vaultmap");
            GZIPOutputStream gzos = new GZIPOutputStream(fos);
            OutputStreamWriter writer = new OutputStreamWriter(gzos);
            Util.MAP_GSON.toJson(snapshot, writer);
            writer.close();
        } catch (IOException e) {
            VaultMapper.LOGGER.error("Couldn't create map save file");
        }
        removeExcessMaps();
    }

    public static void makeSureFoldersExist() {
        if (checkedDirs) {
            return;
        }
        File favs = new File(favoriteMapsFolder);
        File maps = new File(mapSaveFolder);
        if (!favs.exists()) {
            favs.mkdirs();
        }
        if (!maps.exists()) {
            maps.mkdirs();
        }
        checkedDirs = true;
    }


    public static void removeExcessMaps() {
        int maxMaps = ClientConfig.MAX_MAPS_SAVED.get();
        if (maxMaps < 0) {
            return;
        }
        File folder = new File(mapSaveFolder);
        File[] files = folder.listFiles();
        if (files == null || files.length <= maxMaps) {
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        int filesToDelete = files.length - maxMaps;
        for (int i =0; i < filesToDelete; i++) {
            files[i].delete();
        }
    }

    public static Optional<MapSnapshot> from(UUID uuid) {
        return from(uuid.toString());
    }
    public static Optional<MapSnapshot> from(String filename) {
        makeSureFoldersExist();
        String mapPath = mapSaveFolder + filename + ".vaultmap";
        String favPath = favoriteMapsFolder + filename + ".vaultmap";

        Optional<MapSnapshot> normalMap = readMapFromPath(mapPath);
        if (normalMap.isPresent()) {
            return normalMap;
        }

        return readMapFromPath(favPath);
    }
    public static Optional<MapSnapshot> readMapFromPath(String path) {
        File mapFile = new File(path);
        if (!mapFile.exists()) {
            return Optional.empty();
        }

        try {
//            FileReader reader = new FileReader(path);
//            Type saveType = new TypeToken<MapSnapshot>() {}.getType();
//            return Optional.of(gson.fromJson(reader, saveType));

            FileInputStream fis = new FileInputStream(path);
            GZIPInputStream gzis = new GZIPInputStream(fis);
            InputStreamReader reader = new InputStreamReader(gzis);
            Type saveType = new TypeToken<MapSnapshot>() {}.getType();
            return Optional.of(Util.MAP_GSON.fromJson(reader, saveType));
        } catch (IOException e) {
            VaultMapper.LOGGER.error("Couldn't read map save file");
        }
        return Optional.empty();
    }

    @SerializedName("v")
    public int version = 2;

    @SerializedName("cl")
    public List<VaultCell> cells;

    public MapSnapshot(List<VaultCell> cells) {
        this.cells = cells;
    }

    public static void openScreen(String uuid) {
        Minecraft.getInstance().setScreen(new VaultMapScreen(uuid));
    }
    public void openScreen() {
        Minecraft.getInstance().setScreen(new VaultMapScreen(Optional.of(this)));
    }
}
