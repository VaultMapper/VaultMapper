package com.nodiumhosting.vaultmapper.map.snapshots;

import com.google.gson.reflect.TypeToken;
import com.nodiumhosting.vaultmapper.VaultMapper;
import com.nodiumhosting.vaultmapper.map.VaultCell;
import com.nodiumhosting.vaultmapper.map.VaultMap;
import com.nodiumhosting.vaultmapper.util.Util;

import java.io.*;
import java.lang.reflect.Type;
import java.util.concurrent.CopyOnWriteArrayList;

public class MapCache {
    public static String cachePath = "vaultmaps/cache.json";

    public static void deleteCache() {
        MapSnapshot.makeSureFoldersExist();
        File mapFile = new File(cachePath);
        if (mapFile.exists()) {
            mapFile.delete();
        }
    }

    public static void updateCache() {
        MapSnapshot.makeSureFoldersExist();
        try {
            FileWriter writer = new FileWriter(cachePath);
            Util.MAP_GSON.toJson(VaultMap.cells, writer);
            writer.close();
        } catch (IOException e) {
            VaultMapper.LOGGER.error("Couldn't create map cache file");
        }
    }

    public static void readCache() {
        MapSnapshot.makeSureFoldersExist();
        File mapFile = new File(cachePath);
        if (!mapFile.exists()) {
            return;
        }

        try {
            FileReader reader = new FileReader(cachePath);
            Type saveType = new TypeToken<CopyOnWriteArrayList<VaultCell>>(){}.getType();
            VaultMap.cells = Util.MAP_GSON.fromJson(reader, saveType);
            VaultMap.refreshCache();
        } catch (FileNotFoundException e) {}
    }

}
