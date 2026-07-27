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

public class RoomSignatureConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DEFAULT_RESOURCE = "/room_signatures.default.json";

    private static final Path CONFIG_DIR = FMLPaths.CONFIGDIR.get().resolve("vaultmapper");
    private static final Path CONFIG_PATH = CONFIG_DIR.resolve("room_signatures.json");

    private static volatile RoomSignatureConfig activeConfig = new RoomSignatureConfig();

    public static void init() {
        reload();
    }

    public static synchronized boolean reload() {
        try {
            ensureConfigExists();
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
                RoomSignatureConfig loaded = GSON.fromJson(reader, RoomSignatureConfig.class);
                if (loaded == null || loaded.rooms == null) {
                    VaultMapper.LOGGER.error("Room signature config is empty or invalid: {}", CONFIG_PATH);
                    return false;
                }
                activeConfig = loaded;
                VaultMapper.LOGGER.info("Loaded {} room signature entries from {}", loaded.rooms.size(), CONFIG_PATH);
                return true;
            }
        } catch (Exception e) {
            VaultMapper.LOGGER.error("Failed to load room signature config {}: {}", CONFIG_PATH, e.getMessage());
            return false;
        }
    }

    public static RoomSignatureConfig getActiveConfig() {
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

        try (InputStream in = RoomSignatureConfigManager.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new IOException("Missing bundled default room signatures resource: " + DEFAULT_RESOURCE);
            }
            Files.copy(in, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING);
            VaultMapper.LOGGER.info("Created default room signature config at {}", CONFIG_PATH);
        }
    }
}
