package com.nodiumhosting.vaultmapper.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.nodiumhosting.vaultmapper.VaultMapper;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Manages loading and caching of room special detection configuration.
 * Defines which features to scan for and how to parse them.
 */
public class RoomSpecialDetectionConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DEFAULT_RESOURCE = "/room_special_detection.default.json";

    private static final Path CONFIG_DIR = FMLPaths.CONFIGDIR.get().resolve("vaultmapper");
    private static final Path CONFIG_PATH = CONFIG_DIR.resolve("room_special_detection.json");

    private static volatile RoomSpecialDetectionConfig activeConfig = new RoomSpecialDetectionConfig();

    public static void init() {
        reload();
    }

    public static synchronized boolean reload() {
        try {
            ensureConfigExists();
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
                RoomSpecialDetectionConfig loaded = GSON.fromJson(reader, RoomSpecialDetectionConfig.class);
                if (loaded == null || loaded.features == null) {
                    VaultMapper.LOGGER.error("Room special detection config is empty or invalid: {}", CONFIG_PATH);
                    return false;
                }

                // Merge with bundled defaults to ensure new features are included
                RoomSpecialDetectionConfig defaults = loadBundledDefaults();
                if (defaults != null && defaults.features != null) {
                    mergeWithDefaults(loaded, defaults);
                }

                activeConfig = loaded;
                VaultMapper.LOGGER.info("Loaded {} room special feature definitions from {}", loaded.features.size(), CONFIG_PATH);
                return true;
            }
        } catch (Exception e) {
            VaultMapper.LOGGER.error("Failed to load room special detection config {}: {}", CONFIG_PATH, e.getMessage());
            return false;
        }
    }

    public static RoomSpecialDetectionConfig getActiveConfig() {
        return activeConfig;
    }

    public static Path getConfigPath() {
        return CONFIG_PATH;
    }

    private static void ensureConfigExists() throws IOException {
        if (!Files.exists(CONFIG_DIR)) {
            Files.createDirectories(CONFIG_DIR);
        }

        if (Files.exists(CONFIG_PATH)) {
            return;
        }

        try (InputStream in = RoomSpecialDetectionConfigManager.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new IOException("Missing bundled default room special detection resource: " + DEFAULT_RESOURCE);
            }
            Files.copy(in, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING);
            VaultMapper.LOGGER.info("Created default room special detection config at {}", CONFIG_PATH);
        }
    }

    private static RoomSpecialDetectionConfig loadBundledDefaults() {
        try (InputStream in = RoomSpecialDetectionConfigManager.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                VaultMapper.LOGGER.warn("Could not locate bundled default room special detection resource");
                return null;
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return GSON.fromJson(content, RoomSpecialDetectionConfig.class);
        } catch (Exception e) {
            VaultMapper.LOGGER.warn("Failed to load bundled defaults for room special detection: {}", e.getMessage());
            return null;
        }
    }

    private static void mergeWithDefaults(RoomSpecialDetectionConfig loaded, RoomSpecialDetectionConfig defaults) {
        if (loaded.features == null) {
            loaded.features = new java.util.ArrayList<>();
        }
        if (defaults.features == null || defaults.features.isEmpty()) {
            return;
        }

        // For each feature in defaults, check if it exists in loaded
        // If not, add it from defaults
        for (RoomSpecialFeatureDefinition defaultDef : defaults.features) {
            if (defaultDef == null || defaultDef.id == null) {
                continue;
            }

            boolean found = false;
            for (RoomSpecialFeatureDefinition loadedDef : loaded.features) {
                if (loadedDef != null && defaultDef.id.equals(loadedDef.id)) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                // Feature not found in loaded config, add it from defaults
                loaded.features.add(defaultDef);
                VaultMapper.LOGGER.info("Added new room special feature from defaults: {}", defaultDef.id);
            }
        }
    }
}
