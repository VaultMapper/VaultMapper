package com.nodiumhosting.vaultmapper.map.special;

import com.nodiumhosting.vaultmapper.config.BrazierTargetConfigManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;

public final class RoomSpecialNbtParser {
    private RoomSpecialNbtParser() {
    }

    public static Integer findMatchingBrazierTargetIndex(CompoundTag modifiersTag) {
        if (modifiersTag == null || modifiersTag.isEmpty()) {
            return null;
        }

        var config = BrazierTargetConfigManager.getActiveConfig();
        if (config == null || config.desiredModifierSets == null || config.desiredModifierSets.isEmpty()) {
            return null;
        }

        Set<String> currentModifiers = new LinkedHashSet<>();
        for (String key : modifiersTag.getAllKeys()) {
            String normalized = normalizeModifierKey(key);
            if (!normalized.isEmpty()) {
                currentModifiers.add(normalized);
            }
        }
        if (currentModifiers.isEmpty()) {
            return null;
        }

        Integer subsetMatchIndex = null;
        for (int i = 0; i < config.desiredModifierSets.size(); i++) {
            List<String> targetSet = config.desiredModifierSets.get(i);
            if (targetSet == null || targetSet.isEmpty()) {
                continue;
            }

            Set<String> normalizedTarget = new LinkedHashSet<>();
            for (String target : targetSet) {
                String normalized = normalizeModifierKey(target);
                if (!normalized.isEmpty()) {
                    normalizedTarget.add(normalized);
                }
            }
            if (normalizedTarget.isEmpty()) {
                continue;
            }

            if (normalizedTarget.equals(currentModifiers)) {
                return i;
            }

            if (subsetMatchIndex == null && currentModifiers.containsAll(normalizedTarget)) {
                subsetMatchIndex = i;
            }
        }

        return subsetMatchIndex;
    }

    public static String extractGodName(BlockState state, CompoundTag nbt) {
        String completionPool = nbt.getString("modifierCompletionPool");
        if (completionPool != null && !completionPool.isEmpty()) {
            String pool = completionPool;
            int colonAt = pool.indexOf(':');
            if (colonAt >= 0 && colonAt + 1 < pool.length()) {
                pool = pool.substring(colonAt + 1);
            }
            pool = pool.replace("_favours", "");
            String parsed = prettifyVaultName(pool);
            if (!isUnresolvedGodName(parsed)) {
                return parsed;
            }
        }

        if (state != null) {
            String stateString = state.toString();
            int godAt = stateString.indexOf("god=");
            if (godAt >= 0) {
                int valueStart = godAt + 4;
                int valueEnd = stateString.indexOf(',', valueStart);
                if (valueEnd < 0) {
                    valueEnd = stateString.indexOf(']', valueStart);
                }
                if (valueEnd > valueStart) {
                    String parsed = prettifyVaultName(stateString.substring(valueStart, valueEnd));
                    if (!isUnresolvedGodName(parsed)) {
                        return parsed;
                    }
                }
            }
        }

        return "Unknown";
    }

    public static String extractGodChallengeTitle(CompoundTag nbt) {
        CompoundTag taskPool = nbt.getCompound("taskPool");
        CompoundTag renderer = taskPool.getCompound("renderer");
        String title = renderer.getString("title");
        if (title == null || title.isEmpty()) {
            return "Unknown Challenge";
        }
        return title;
    }

    public static String parseBrazierModifiers(CompoundTag nbt) {
        if (nbt == null) {
            return null;
        }

        CompoundTag modifiers = nbt.getCompound("Modifiers");
        if (modifiers == null || modifiers.isEmpty()) {
            return null;
        }

        Set<String> readableModifiers = new LinkedHashSet<>();
        for (String key : modifiers.getAllKeys()) {
            String pretty = prettifyVaultName(key);
            if (!pretty.isEmpty()) {
                readableModifiers.add(pretty);
            }
        }

        if (readableModifiers.isEmpty()) {
            return null;
        }

        return String.join(" + ", readableModifiers);
    }

    public static String extractPylonDescription(CompoundTag nbt) {
        if (nbt == null) {
            return null;
        }

        CompoundTag config = nbt.getCompound("Config");
        if (config == null || config.isEmpty()) {
            return null;
        }

        String description = config.getString("description");
        if (description != null && !description.isEmpty()) {
            return description;
        }

        String type = config.getString("type");
        if (type != null && !type.isEmpty()) {
            return prettifyVaultName(type);
        }

        return null;
    }

    public static String extractPylonType(CompoundTag nbt) {
        if (nbt == null) {
            return null;
        }

        CompoundTag config = nbt.getCompound("Config");
        if (config == null || config.isEmpty()) {
            return null;
        }

        String type = config.getString("type");
        return (type != null && !type.isEmpty()) ? type : null;
    }

    public static String getVerticalRelationKey(int positionY, int playerY) {
        int dy = positionY - playerY;
        if (dy > 0) {
            return "UP";
        }
        if (dy < 0) {
            return "DOWN";
        }
        return "SAME";
    }

    private static String prettifyVaultName(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }

        String value = raw;
        if (value.contains(":")) {
            value = value.substring(value.indexOf(':') + 1);
        }

        String[] parts = value.split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.toString();
    }

    private static String normalizeModifierKey(String key) {
        if (key == null) {
            return "";
        }
        return key.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isUnresolvedGodName(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "default".equals(normalized) || "unknown".equals(normalized);
    }
}
