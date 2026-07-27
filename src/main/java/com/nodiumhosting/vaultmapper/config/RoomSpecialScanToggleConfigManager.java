package com.nodiumhosting.vaultmapper.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.nodiumhosting.vaultmapper.VaultMapper;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RoomSpecialScanToggleConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR = FMLPaths.CONFIGDIR.get().resolve("vaultmapper");
    private static final Path CONFIG_PATH = CONFIG_DIR.resolve("room_special_scan_toggles.json");

    private static volatile RoomSpecialScanToggleConfig activeConfig = new RoomSpecialScanToggleConfig();

    public static void init() {
        reload();
    }

    public static synchronized boolean reload() {
        try {
            ensureConfigExists();
            RoomSpecialScanToggleConfig loaded;
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
                loaded = GSON.fromJson(reader, RoomSpecialScanToggleConfig.class);
            }
            if (loaded == null) {
                loaded = new RoomSpecialScanToggleConfig();
            }
            if (loaded.featureToggles == null) {
                loaded.featureToggles = new LinkedHashMap<>();
            }

            syncWithActiveDetectionConfig(loaded);
            activeConfig = loaded;
            save();
            VaultMapper.LOGGER.info("Loaded room special scan toggles from {}", CONFIG_PATH);
            return true;
        } catch (Exception e) {
            VaultMapper.LOGGER.error("Failed to load room special scan toggles {}: {}", CONFIG_PATH, e.getMessage());
            return false;
        }
    }

    public static synchronized boolean isEnabled(String featureId) {
        if (featureId == null || featureId.isEmpty()) {
            return false;
        }
        ensureSynced();
        return Boolean.TRUE.equals(activeConfig.featureToggles.get(featureId));
    }

    public static synchronized void setEnabled(String featureId, boolean enabled) {
        if (featureId == null || featureId.isEmpty()) {
            return;
        }
        ensureSynced();
        activeConfig.featureToggles.put(featureId, enabled);
        save();
    }

    public static synchronized void toggle(String featureId) {
        setEnabled(featureId, !isEnabled(featureId));
    }

    public static synchronized void setAllEnabled(boolean enabled) {
        ensureSynced();
        RoomSpecialDetectionConfig detectionConfig = RoomSpecialDetectionConfigManager.getActiveConfig();
        if (detectionConfig == null || detectionConfig.getEnabledFeatures() == null) {
            return;
        }
        for (RoomSpecialFeatureDefinition definition : detectionConfig.getEnabledFeatures()) {
            if (definition == null || definition.id == null || definition.id.isEmpty()) {
                continue;
            }
            activeConfig.featureToggles.put(definition.id, enabled);
        }
        save();
    }

    public static synchronized Map<String, Boolean> getFeatureToggles() {
        ensureSynced();
        return new LinkedHashMap<>(activeConfig.featureToggles);
    }

    public static Path getConfigPath() {
        return CONFIG_PATH;
    }

    private static void ensureSynced() {
        if (activeConfig == null) {
            activeConfig = new RoomSpecialScanToggleConfig();
        }
        if (activeConfig.featureToggles == null) {
            activeConfig.featureToggles = new LinkedHashMap<>();
        }
        syncWithActiveDetectionConfig(activeConfig);
    }

    private static void syncWithActiveDetectionConfig(RoomSpecialScanToggleConfig config) {
        RoomSpecialDetectionConfig detectionConfig = RoomSpecialDetectionConfigManager.getActiveConfig();
        if (detectionConfig == null || detectionConfig.getEnabledFeatures() == null) {
            return;
        }

        for (RoomSpecialFeatureDefinition definition : detectionConfig.getEnabledFeatures()) {
            if (definition == null || definition.id == null || definition.id.isEmpty()) {
                continue;
            }
            config.featureToggles.putIfAbsent(definition.id, false);
        }
    }

    private static void ensureConfigExists() throws IOException {
        if (!Files.exists(CONFIG_DIR)) {
            Files.createDirectories(CONFIG_DIR);
        }
        if (Files.exists(CONFIG_PATH)) {
            return;
        }

        RoomSpecialScanToggleConfig config = new RoomSpecialScanToggleConfig();
        try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
            GSON.toJson(config, writer);
        }
        VaultMapper.LOGGER.info("Created default room special scan toggle config at {}", CONFIG_PATH);
    }

    private static synchronized void save() {
        try {
            if (!Files.exists(CONFIG_DIR)) {
                Files.createDirectories(CONFIG_DIR);
            }
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
                GSON.toJson(activeConfig, writer);
            }
        } catch (IOException e) {
            VaultMapper.LOGGER.error("Failed to save room special scan toggles {}: {}", CONFIG_PATH, e.getMessage());
        }
    }
}
