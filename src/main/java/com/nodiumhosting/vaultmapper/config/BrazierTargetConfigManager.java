package com.nodiumhosting.vaultmapper.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nodiumhosting.vaultmapper.VaultMapper;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public class BrazierTargetConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DEFAULT_RESOURCE = "/brazier_targets.default.json";

    private static final Path CONFIG_DIR = FMLPaths.CONFIGDIR.get().resolve("vaultmapper");
    private static final Path CONFIG_PATH = CONFIG_DIR.resolve("brazier_targets.json");

    private static volatile BrazierTargetConfig activeConfig = new BrazierTargetConfig();

    public static void init() {
        reload();
    }

    public static synchronized boolean reload() {
        try {
            ensureConfigExists();
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);

                BrazierTargetConfig loadedConfig = new BrazierTargetConfig();
                parseIntoConfig(root, loadedConfig);

                activeConfig = loadedConfig;
                VaultMapper.LOGGER.info("Loaded {} brazier target modifier set(s) from {}", loadedConfig.desiredModifierSets.size(), CONFIG_PATH);
                return true;
            }
        } catch (Exception e) {
            VaultMapper.LOGGER.error("Failed to load brazier target config {}: {}", CONFIG_PATH, e.getMessage());
            return false;
        }
    }

    public static BrazierTargetConfig getActiveConfig() {
        return activeConfig;
    }

    public static Path getConfigPath() {
        return CONFIG_PATH;
    }

    private static List<List<String>> sanitize(List<List<String>> input) {
        List<List<String>> sanitized = new ArrayList<>();
        for (List<String> set : input) {
            if (set == null || set.isEmpty()) {
                continue;
            }

            ArrayList<String> cleanedSet = new ArrayList<>();
            for (String value : set) {
                if (value == null) {
                    continue;
                }
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) {
                    cleanedSet.add(trimmed);
                }
            }

            if (!cleanedSet.isEmpty()) {
                sanitized.add(cleanedSet);
            }
        }
        return sanitized;
    }

    private static void parseIntoConfig(JsonElement root, BrazierTargetConfig destination) {
        if (root == null || root.isJsonNull() || !root.isJsonArray()) {
            return;
        }

        JsonArray entries = root.getAsJsonArray();
        int generatedId = 1;

        for (JsonElement entry : entries) {
            if (entry == null || entry.isJsonNull()) {
                continue;
            }

            List<String> modifiers = null;
            String identifier = null;

            if (entry.isJsonArray()) {
                modifiers = readModifierArray(entry.getAsJsonArray());
            } else if (entry.isJsonObject()) {
                JsonObject object = entry.getAsJsonObject();
                if (object.has("id") && object.get("id").isJsonPrimitive()) {
                    identifier = object.get("id").getAsString();
                }
                if (object.has("modifiers") && object.get("modifiers").isJsonArray()) {
                    modifiers = readModifierArray(object.getAsJsonArray("modifiers"));
                }
            }

            if (modifiers == null || modifiers.isEmpty()) {
                continue;
            }

            String cleanedIdentifier = identifier == null ? "" : identifier.trim();
            if (cleanedIdentifier.isEmpty()) {
                cleanedIdentifier = String.valueOf(generatedId);
            }

            destination.identifiers.add(cleanedIdentifier);
            destination.desiredModifierSets.add(modifiers);
            generatedId++;
        }
    }

    private static List<String> readModifierArray(JsonArray array) {
        if (array == null || array.size() == 0) {
            return null;
        }

        ArrayList<String> cleaned = new ArrayList<>();
        for (JsonElement value : array) {
            if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
                continue;
            }
            String trimmed = value.getAsString().trim();
            if (!trimmed.isEmpty()) {
                cleaned.add(trimmed);
            }
        }

        if (cleaned.isEmpty()) {
            return null;
        }

        return cleaned;
    }

    public static String getIdentifierForIndex(Integer index) {
        if (index == null || index < 0) {
            return null;
        }

        BrazierTargetConfig config = activeConfig;
        if (config == null || config.identifiers == null || index >= config.identifiers.size()) {
            return null;
        }

        String identifier = config.identifiers.get(index);
        if (identifier == null) {
            return null;
        }
        String trimmed = identifier.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void ensureConfigExists() throws IOException {
        if (!Files.exists(CONFIG_DIR)) {
            Files.createDirectories(CONFIG_DIR);
        }

        if (Files.exists(CONFIG_PATH)) {
            return;
        }

        try (InputStream in = BrazierTargetConfigManager.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new IOException("Missing bundled default brazier targets resource: " + DEFAULT_RESOURCE);
            }
            Files.copy(in, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING);
            VaultMapper.LOGGER.info("Created default brazier target config at {}", CONFIG_PATH);
        }
    }
}
