package com.nodiumhosting.vaultmapper.map.special;

import com.nodiumhosting.vaultmapper.config.BrazierTargetConfigManager;
import com.nodiumhosting.vaultmapper.config.RoomSpecialDetectionConfig;
import com.nodiumhosting.vaultmapper.config.RoomSpecialDetectionConfigManager;
import com.nodiumhosting.vaultmapper.config.RoomSpecialFeatureDefinition;
import com.nodiumhosting.vaultmapper.config.RoomSpecialScanToggleConfigManager;
import com.nodiumhosting.vaultmapper.map.VaultMap;
import com.nodiumhosting.vaultmapper.proto.CellType;
import com.nodiumhosting.vaultmapper.proto.RoomType;
import com.nodiumhosting.vaultmapper.util.CellCoordinate;
import com.nodiumhosting.vaultmapper.util.VaultDimensionUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RoomSpecialFeatureCoordinator {
    private static final int ROOM_SPECIAL_SCAN_BUDGET_PER_TICK = 3000;
    private static final int MAX_GOD_ALTARS_PER_ROOM = 2;
    private static final int MAX_PYLONS_PER_ROOM = 3;
    private static final int ROOM_SPECIAL_SCAN_Y_RANGE = 30;

    private static CellCoordinate currentRoomSpecialScanCoord = null;
    private static int currentRoomSpecialScanMinY = 0;
    private static int currentRoomSpecialScanMaxY = 0;
    private static int currentRoomSpecialScanCursor = 0;
    private static boolean currentRoomSpecialScanDone = false;
    private static boolean currentRoomSpecialScanGodEnabled = false;
    private static boolean currentRoomSpecialScanBrazierEnabled = false;
    private static boolean currentRoomSpecialScanPylonEnabled = false;
    private static String currentRoomBrazierModifiersText = null;
    private static BlockPos currentRoomBrazierPos = null;
    private static Integer currentRoomBrazierMatchIndex = null;
    private static String currentRoomCakeText = null;
    private static BlockPos currentRoomCakePos = null;
    private static final ArrayList<RoomSpecialPoint> currentRoomGodAltars = new ArrayList<>();
    private static final ArrayList<RoomSpecialPoint> currentRoomPylons = new ArrayList<>();
    private static String currentRoomBrazierAnnouncedText = null;
    private static String currentRoomCakeAnnouncedText = null;
    private static final Set<String> currentRoomGodAltarAnnouncedLines = new LinkedHashSet<>();
    private static final Set<String> currentRoomPylonAnnouncedLines = new LinkedHashSet<>();
    private static final ConcurrentHashMap<CellCoordinate, RoomSpecialDetectionCacheEntry> roomSpecialDetectionCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<CellCoordinate, RoomSpecialScanProgressEntry> roomSpecialScanProgressCache = new ConcurrentHashMap<>();

    public static String getCurrentRoomBrazierOverlayText() {
        if (currentRoomBrazierModifiersText == null || currentRoomBrazierPos == null) {
            return null;
        }
        int playerY = Minecraft.getInstance().player == null ? currentRoomBrazierPos.getY() : Minecraft.getInstance().player.blockPosition().getY();
        return getVerticalRelationKey(currentRoomBrazierPos, playerY) + "|" + currentRoomBrazierModifiersText;
    }

    public static BlockPos getCurrentRoomBrazierPosition() {
        return currentRoomBrazierPos;
    }

    public static BlockPos getCurrentRoomCakePosition() {
        return currentRoomCakePos;
    }

    public static List<BlockPos> getCurrentRoomGodAltarPositions() {
        ArrayList<BlockPos> positions = new ArrayList<>();
        for (RoomSpecialPoint point : currentRoomGodAltars) {
            if (point != null && point.position != null) {
                positions.add(point.position);
            }
        }
        return positions;
    }

    public static List<BlockPos> getCurrentRoomPylonPositions() {
        ArrayList<BlockPos> positions = new ArrayList<>();
        for (RoomSpecialPoint point : currentRoomPylons) {
            if (point != null && point.position != null) {
                positions.add(point.position);
            }
        }
        return positions;
    }

    public static String getCurrentRoomCakeOverlayText() {
        if (currentRoomCakeText == null || currentRoomCakePos == null) {
            return null;
        }
        int playerY = Minecraft.getInstance().player == null ? currentRoomCakePos.getY() : Minecraft.getInstance().player.blockPosition().getY();
        return getVerticalRelationKey(currentRoomCakePos, playerY) + "|" + currentRoomCakeText;
    }

    public static List<String> getCurrentRoomPylonOverlayLines() {
        ArrayList<String> lines = new ArrayList<>();
        int playerY = Minecraft.getInstance().player == null ? 0 : Minecraft.getInstance().player.blockPosition().getY();
        for (RoomSpecialPoint pylon : currentRoomPylons) {
            if (pylon == null || pylon.text == null || pylon.text.isEmpty()) continue;
            String displayText = pylon.text;
            if ("time".equals(pylon.type)) {
                displayText = "\u23F1 " + displayText + " \u23F1";
            }
            lines.add(getVerticalRelationKey(pylon.position, playerY) + "|" + displayText);
        }
        return lines;
    }

    public static List<String> getCurrentRoomGodAltarOverlayLines() {
        ArrayList<String> lines = new ArrayList<>();
        int playerY = Minecraft.getInstance().player == null ? 0 : Minecraft.getInstance().player.blockPosition().getY();
        for (RoomSpecialPoint altar : currentRoomGodAltars) {
            if (altar == null || altar.text == null) continue;
            String lower = altar.text.toLowerCase();
            if (lower.startsWith("default:") || lower.startsWith("unknown:")) continue;
            lines.add(getVerticalRelationKey(altar.position, playerY) + "|" + altar.text);
        }
        return lines;
    }

    public static boolean currentRoomBrazierMatchesTarget() {
        return currentRoomBrazierMatchIndex != null;
    }

    public static Integer getRoomBrazierMatchIndex(CellCoordinate coord) {
        if (coord == null) return null;
        RoomSpecialDetectionCacheEntry cached = roomSpecialDetectionCache.get(coord);
        if (cached == null || cached.brazierMatchIndex == null) return null;
        return cached.brazierMatchIndex + 1;
    }

    public static String getRoomBrazierMatchIdentifier(CellCoordinate coord) {
        if (coord == null) return null;
        RoomSpecialDetectionCacheEntry cached = roomSpecialDetectionCache.get(coord);
        if (cached == null || cached.brazierMatchIndex == null) return null;
        String identifier = BrazierTargetConfigManager.getIdentifierForIndex(cached.brazierMatchIndex);
        if (identifier != null && !identifier.isEmpty()) return identifier;
        return String.valueOf(cached.brazierMatchIndex + 1);
    }

    public static List<Integer> getRoomGodAltarIndicatorColors(CellCoordinate coord) {
        ArrayList<Integer> colors = new ArrayList<>();
        if (coord == null) return colors;
        RoomSpecialDetectionCacheEntry cached = roomSpecialDetectionCache.get(coord);
        if (cached == null || cached.godAltars == null || cached.godAltars.isEmpty()) return colors;
        for (RoomSpecialPoint altar : cached.godAltars) {
            if (altar == null || altar.text == null || altar.text.isEmpty()) continue;
            int colon = altar.text.indexOf(':');
            String godName = colon > 0 ? altar.text.substring(0, colon).trim() : altar.text.trim();
            if (godName.isEmpty()) continue;
            String lower = godName.toLowerCase();
            if ("default".equals(lower) || "unknown".equals(lower)) continue;
            int color = RoomSpecialTextRenderer.getGodNameColor(godName);
            if (color == 0xFFFFFF) continue;
            colors.add(color);
            if (colors.size() >= 2) break;
        }
        return colors;
    }

    public static List<Integer> getRoomPylonIndicatorColors(CellCoordinate coord) {
        ArrayList<Integer> colors = new ArrayList<>();
        if (coord == null) return colors;
        RoomSpecialDetectionCacheEntry cached = roomSpecialDetectionCache.get(coord);
        if (cached == null || cached.pylons == null || cached.pylons.isEmpty()) return colors;
        for (RoomSpecialPoint pylon : cached.pylons) {
            if (pylon != null && "time".equals(pylon.type)) {
                colors.add(0xFF6600FF);
            } else {
                colors.add(0xFFD166);
            }
        }
        return colors;
    }

    public static List<BlockPos> getRoomPylonPositions(CellCoordinate coord) {
        ArrayList<BlockPos> positions = new ArrayList<>();
        if (coord == null) return positions;
        RoomSpecialDetectionCacheEntry cached = roomSpecialDetectionCache.get(coord);
        if (cached == null || cached.pylons == null || cached.pylons.isEmpty()) return positions;
        for (RoomSpecialPoint p : cached.pylons) {
            if (p != null && p.position != null) positions.add(p.position);
        }
        return positions;
    }

    public static List<BlockPos> getRoomGodAltarPositions(CellCoordinate coord) {
        ArrayList<BlockPos> positions = new ArrayList<>();
        if (coord == null) return positions;
        RoomSpecialDetectionCacheEntry cached = roomSpecialDetectionCache.get(coord);
        if (cached == null || cached.godAltars == null || cached.godAltars.isEmpty()) return positions;
        for (RoomSpecialPoint p : cached.godAltars) {
            if (p != null && p.position != null) positions.add(p.position);
        }
        return positions;
    }

    public static List<BlockPos> getRoomCakePositions(CellCoordinate coord) {
        ArrayList<BlockPos> positions = new ArrayList<>();
        if (coord == null) return positions;
        RoomSpecialDetectionCacheEntry cached = roomSpecialDetectionCache.get(coord);
        if (cached == null) return positions;
        if (cached.cakePosition != null) positions.add(cached.cakePosition);
        return positions;
    }

    public static List<String> getRoomPylonTypes(CellCoordinate coord) {
        ArrayList<String> types = new ArrayList<>();
        if (coord == null) return types;
        RoomSpecialDetectionCacheEntry cached = roomSpecialDetectionCache.get(coord);
        if (cached == null || cached.pylons == null || cached.pylons.isEmpty()) return types;
        for (RoomSpecialPoint pylon : cached.pylons) {
            types.add(pylon.type);
        }
        return types;
    }

    public static List<Integer> getRoomCakeIndicatorColors(CellCoordinate coord) {
        ArrayList<Integer> colors = new ArrayList<>();
        if (coord == null) return colors;
        RoomSpecialDetectionCacheEntry cached = roomSpecialDetectionCache.get(coord);
        if (cached == null || cached.cakeText == null || cached.cakeText.isEmpty()) return colors;
        colors.add(0xFFD166);
        return colors;
    }

    public static void invalidateRoomSpecialDetections() {
        roomSpecialDetectionCache.clear();
        roomSpecialScanProgressCache.clear();
        clearCurrentRoomSpecialDetections();
    }

    // --- Internal methods ---

    private static String getVerticalRelationKey(BlockPos position, int playerY) {
        return RoomSpecialNbtParser.getVerticalRelationKey(position.getY(), playerY);
    }

    static void clearCurrentRoomSpecialDetections() {
        currentRoomSpecialScanCoord = null;
        currentRoomSpecialScanMinY = 0;
        currentRoomSpecialScanMaxY = 0;
        currentRoomSpecialScanCursor = 0;
        currentRoomSpecialScanDone = false;
        currentRoomSpecialScanGodEnabled = false;
        currentRoomSpecialScanBrazierEnabled = false;
        currentRoomSpecialScanPylonEnabled = false;
        currentRoomBrazierModifiersText = null;
        currentRoomBrazierPos = null;
        currentRoomBrazierMatchIndex = null;
        currentRoomCakeText = null;
        currentRoomCakePos = null;
        currentRoomGodAltars.clear();
        currentRoomPylons.clear();
        currentRoomBrazierAnnouncedText = null;
        currentRoomCakeAnnouncedText = null;
        currentRoomGodAltarAnnouncedLines.clear();
        currentRoomPylonAnnouncedLines.clear();
    }

    private static void upsertGodAltarPoint(ArrayList<RoomSpecialPoint> altars, BlockPos position, String line) {
        for (int i = 0; i < altars.size(); i++) {
            RoomSpecialPoint existing = altars.get(i);
            if (!existing.position.equals(position)) continue;
            if (!existing.text.equals(line)) {
                altars.set(i, new RoomSpecialPoint(line, position));
            }
            return;
        }
        if (altars.size() < MAX_GOD_ALTARS_PER_ROOM) {
            altars.add(new RoomSpecialPoint(line, position));
        }
    }

    private static void upsertSpecialPoint(ArrayList<RoomSpecialPoint> points, int maxPoints, BlockPos position, String line, String type) {
        for (int i = 0; i < points.size(); i++) {
            RoomSpecialPoint existing = points.get(i);
            if (!existing.position.equals(position)) continue;
            if (!existing.text.equals(line) || !java.util.Objects.equals(existing.type, type)) {
                points.set(i, new RoomSpecialPoint(line, position, type));
            }
            return;
        }
        if (points.size() < maxPoints) {
            points.add(new RoomSpecialPoint(line, position, type));
        }
    }

    private static void upsertSpecialPoint(ArrayList<RoomSpecialPoint> points, int maxPoints, BlockPos position, String line) {
        upsertSpecialPoint(points, maxPoints, position, line, null);
    }

    private static boolean hasOnlyResolvedGodAltars(List<RoomSpecialPoint> altars) {
        if (altars == null || altars.isEmpty()) return false;
        for (RoomSpecialPoint altar : altars) {
            if (altar == null || altar.text == null) return false;
            String lower = altar.text.toLowerCase();
            if (lower.startsWith("default:") || lower.startsWith("unknown:")) return false;
        }
        return true;
    }

    private static Integer findMatchingBrazierTargetIndex(CompoundTag modifiersTag) {
        return RoomSpecialNbtParser.findMatchingBrazierTargetIndex(modifiersTag);
    }

    private static String extractGodName(BlockState state, CompoundTag nbt) {
        return RoomSpecialNbtParser.extractGodName(state, nbt);
    }

    private static String extractGodChallengeTitle(CompoundTag nbt) {
        return RoomSpecialNbtParser.extractGodChallengeTitle(nbt);
    }

    private static String parseBrazierModifiers(CompoundTag nbt) {
        return RoomSpecialNbtParser.parseBrazierModifiers(nbt);
    }

    private static void startCurrentRoomSpecialScan(CellCoordinate roomCoordinate, int playerY, Player player, boolean scanGod, boolean scanBrazier, boolean scanPylon) {
        if (currentRoomSpecialScanCoord != null
                && (currentRoomSpecialScanCoord.x() != roomCoordinate.x() || currentRoomSpecialScanCoord.z() != roomCoordinate.z())) {
            persistCurrentRoomSpecialDetections();
            persistCurrentRoomSpecialScanProgress();
        }

        currentRoomSpecialScanCoord = roomCoordinate;
        currentRoomSpecialScanCursor = 0;
        currentRoomSpecialScanDone = false;
        currentRoomSpecialScanGodEnabled = scanGod;
        currentRoomSpecialScanBrazierEnabled = scanBrazier;
        currentRoomSpecialScanPylonEnabled = scanPylon;
        currentRoomBrazierModifiersText = null;
        currentRoomBrazierPos = null;
        currentRoomBrazierMatchIndex = null;
        currentRoomCakeText = null;
        currentRoomCakePos = null;
        currentRoomGodAltars.clear();
        currentRoomPylons.clear();
        currentRoomBrazierAnnouncedText = null;
        currentRoomCakeAnnouncedText = null;
        currentRoomGodAltarAnnouncedLines.clear();
        currentRoomPylonAnnouncedLines.clear();

        RoomSpecialDetectionCacheEntry cached = roomSpecialDetectionCache.get(roomCoordinate);
        if (cached != null) {
            if (scanBrazier) {
                currentRoomBrazierModifiersText = cached.brazierModifiers;
                currentRoomBrazierPos = cached.brazierPosition;
                currentRoomBrazierMatchIndex = cached.brazierMatchIndex;
            }
            currentRoomCakeText = cached.cakeText;
            currentRoomCakePos = cached.cakePosition;
            if (scanGod) currentRoomGodAltars.addAll(cached.godAltars);
            if (scanPylon) currentRoomPylons.addAll(cached.pylons);

            boolean brazierSatisfied = !scanBrazier || (currentRoomBrazierModifiersText != null && currentRoomBrazierPos != null);
            boolean cakeSatisfied = currentRoomCakeText == null || currentRoomCakePos != null;
            boolean godSatisfied = !scanGod || currentRoomGodAltars.size() >= MAX_GOD_ALTARS_PER_ROOM;
            boolean pylonSatisfied = !scanPylon || currentRoomPylons.size() >= MAX_PYLONS_PER_ROOM;
            if (brazierSatisfied && cakeSatisfied && godSatisfied && pylonSatisfied) {
                currentRoomSpecialScanDone = true;
                roomSpecialScanProgressCache.remove(roomCoordinate);
                return;
            }
        }

        RoomSpecialScanProgressEntry progress = roomSpecialScanProgressCache.get(roomCoordinate);
        if (progress != null
                && progress.scanGodEnabled == scanGod
                && progress.scanBrazierEnabled == scanBrazier
                && progress.scanPylonEnabled == scanPylon
                && progress.minY <= progress.maxY) {
            currentRoomSpecialScanMinY = progress.minY;
            currentRoomSpecialScanMaxY = progress.maxY;
            int totalChecks = getRoomSpecialTotalChecks(currentRoomSpecialScanMinY, currentRoomSpecialScanMaxY);
            currentRoomSpecialScanCursor = totalChecks > 0 ? Math.max(0, Math.min(progress.cursor, totalChecks - 1)) : 0;
        } else {
            int minBuildY = player.level.getMinBuildHeight();
            int maxBuildY = player.level.getMaxBuildHeight() - 1;
            currentRoomSpecialScanMinY = Math.max(minBuildY, playerY - ROOM_SPECIAL_SCAN_Y_RANGE);
            currentRoomSpecialScanMaxY = Math.min(maxBuildY, playerY + ROOM_SPECIAL_SCAN_Y_RANGE);
            if (currentRoomSpecialScanMinY > currentRoomSpecialScanMaxY) {
                currentRoomSpecialScanMinY = minBuildY;
                currentRoomSpecialScanMaxY = maxBuildY;
            }
            roomSpecialScanProgressCache.remove(roomCoordinate);
        }
    }

    private static void storeRoomSpecialDetectionCache(CellCoordinate roomCoordinate, String brazierModifiers, BlockPos brazierPosition, Integer brazierMatchIndex, String cakeText, BlockPos cakePosition, List<RoomSpecialPoint> godAltars, List<RoomSpecialPoint> pylons) {
        if (roomCoordinate == null) return;
        roomSpecialDetectionCache.put(roomCoordinate, new RoomSpecialDetectionCacheEntry(brazierModifiers, brazierPosition, brazierMatchIndex, cakeText, cakePosition, godAltars, pylons));
    }

    private static boolean hasCurrentRoomSpecialData() {
        return currentRoomBrazierModifiersText != null
                || currentRoomBrazierPos != null
                || currentRoomBrazierMatchIndex != null
                || !currentRoomGodAltars.isEmpty()
                || !currentRoomPylons.isEmpty();
    }

    private static void persistCurrentRoomSpecialDetections() {
        if (currentRoomSpecialScanCoord == null || !hasCurrentRoomSpecialData()) return;
        storeRoomSpecialDetectionCache(
                currentRoomSpecialScanCoord,
                currentRoomBrazierModifiersText,
                currentRoomBrazierPos,
                currentRoomBrazierMatchIndex,
                currentRoomCakeText,
                currentRoomCakePos,
                currentRoomGodAltars,
                currentRoomPylons
        );
    }

    private static int getRoomSpecialTotalChecks(int minY, int maxY) {
        int ySpan = maxY - minY + 1;
        if (ySpan <= 0) return 0;
        return 47 * 47 * ySpan;
    }

    private static void persistCurrentRoomSpecialScanProgress() {
        if (currentRoomSpecialScanCoord == null) return;
        int totalChecks = getRoomSpecialTotalChecks(currentRoomSpecialScanMinY, currentRoomSpecialScanMaxY);
        if (totalChecks <= 0 || currentRoomSpecialScanDone || currentRoomSpecialScanCursor >= totalChecks) {
            roomSpecialScanProgressCache.remove(currentRoomSpecialScanCoord);
            return;
        }
        int clampedCursor = Math.max(0, Math.min(currentRoomSpecialScanCursor, totalChecks - 1));
        roomSpecialScanProgressCache.put(
                currentRoomSpecialScanCoord,
                new RoomSpecialScanProgressEntry(
                        currentRoomSpecialScanMinY, currentRoomSpecialScanMaxY,
                        clampedCursor,
                        currentRoomSpecialScanGodEnabled, currentRoomSpecialScanBrazierEnabled, currentRoomSpecialScanPylonEnabled
                )
        );
    }

    private static boolean shouldSkipSpecialRoomScan(CellCoordinate roomCoordinate) {
        if (roomCoordinate == null) return false;
        var cell = VaultMap.cellCache.get(roomCoordinate);
        if (cell == null) return false;
        if (cell.roomType == RoomType.ROOMTYPE_CHALLENGE
                || cell.roomType == RoomType.ROOMTYPE_OMEGA
                || cell.roomType == RoomType.ROOMTYPE_START) {
            return true;
        }
        return cell.roomName != null && cell.roomName.toLowerCase().contains("boss");
    }

    private static boolean isFeatureScanEnabled(RoomSpecialFeatureDefinition featureDef) {
        if (featureDef == null) return false;
        return RoomSpecialScanToggleConfigManager.isEnabled(featureDef.id);
    }

    private static boolean isFeatureScanCompleted(RoomSpecialFeatureDefinition featureDef) {
        if (featureDef == null || featureDef.id == null) return false;
        if ("brazier".equals(featureDef.id)) return currentRoomBrazierModifiersText != null;
        if ("god_altar".equals(featureDef.id)) {
            int maxAltars = featureDef.maxPerRoom > 0 ? featureDef.maxPerRoom : MAX_GOD_ALTARS_PER_ROOM;
            return currentRoomGodAltars.size() >= maxAltars;
        }
        if ("cake".equals(featureDef.id)) return currentRoomCakeText != null && currentRoomCakePos != null;
        if ("pylon".equals(featureDef.id)) {
            int maxPylons = featureDef.maxPerRoom > 0 ? featureDef.maxPerRoom : MAX_PYLONS_PER_ROOM;
            return currentRoomPylons.size() >= maxPylons;
        }
        return false;
    }

    private static boolean isAllFeaturesScanCompleted(RoomSpecialDetectionConfig detectionConfig, boolean scanGod, boolean scanBrazier) {
        if (detectionConfig != null && detectionConfig.getEnabledFeatures() != null) {
            for (RoomSpecialFeatureDefinition featureDef : detectionConfig.getEnabledFeatures()) {
                if (featureDef == null || !isFeatureScanEnabled(featureDef)) continue;
                if (!isFeatureScanCompleted(featureDef)) return false;
            }
            return true;
        }
        boolean brazierDone = !scanBrazier || currentRoomBrazierModifiersText != null;
        boolean godDone = !scanGod || currentRoomGodAltars.size() >= MAX_GOD_ALTARS_PER_ROOM;
        return brazierDone && godDone;
    }

    private static void processDetectedFeature(RoomSpecialFeatureDefinition featureDef, BlockPos worldPos, CompoundTag nbt, BlockState blockState, Player player) {
        if (featureDef == null || featureDef.id == null) return;
        RoomSpecialFeatureDetector detector = FeatureDetectorRegistry.getDetectorForDefinition(featureDef);
        if (detector == null) {
            if ("brazier".equals(featureDef.id)) processBrazierFeature(nbt, worldPos, player);
            else if ("god_altar".equals(featureDef.id)) processGodAltarFeature(nbt, blockState, worldPos, player);
            return;
        }
        List<DetectedSpecialFeature> features = detector.detectFromNbt(featureDef, blockState, nbt, player);
        for (DetectedSpecialFeature feature : features) {
            feature.position = worldPos;
            if ("brazier".equals(feature.featureId)) {
                currentRoomBrazierModifiersText = feature.displayText;
                currentRoomBrazierPos = worldPos;
                currentRoomBrazierMatchIndex = feature.matchIndex;
                persistCurrentRoomSpecialDetections();
                if (VaultMap.debug && !feature.displayText.equals(currentRoomBrazierAnnouncedText)) {
                    player.sendMessage(new TextComponent("[VaultMapper Debug] Brazier modifiers: " + feature.displayText), player.getUUID());
                    currentRoomBrazierAnnouncedText = feature.displayText;
                }
            } else if ("cake".equals(feature.featureId)) {
                currentRoomCakeText = feature.displayText;
                currentRoomCakePos = worldPos;
                persistCurrentRoomSpecialDetections();
                if (VaultMap.debug && !feature.displayText.equals(currentRoomCakeAnnouncedText)) {
                    player.sendMessage(new TextComponent("[VaultMapper Debug] Cake: " + feature.displayText), player.getUUID());
                    currentRoomCakeAnnouncedText = feature.displayText;
                }
            } else if ("pylon".equals(feature.featureId)) {
                String pylonType = RoomSpecialNbtParser.extractPylonType(nbt);
                upsertSpecialPoint(currentRoomPylons, MAX_PYLONS_PER_ROOM, worldPos, feature.displayText, pylonType);
                persistCurrentRoomSpecialDetections();
                String debugKey = feature.displayText + "@" + worldPos.getX() + "," + worldPos.getY() + "," + worldPos.getZ();
                if (VaultMap.debug && !currentRoomPylonAnnouncedLines.contains(debugKey)) {
                    player.sendMessage(new TextComponent("[VaultMapper Debug] Pylon: " + feature.displayText + (pylonType != null ? " (Type: " + pylonType + ")" : "")), player.getUUID());
                    currentRoomPylonAnnouncedLines.add(debugKey);
                }
            } else if ("god_altar".equals(feature.featureId)) {
                upsertGodAltarPoint(currentRoomGodAltars, worldPos, feature.displayText);
                persistCurrentRoomSpecialDetections();
                String debugKey = feature.displayText + "@" + worldPos.getX() + "," + worldPos.getY() + "," + worldPos.getZ();
                if (VaultMap.debug && !currentRoomGodAltarAnnouncedLines.contains(debugKey)) {
                    player.sendMessage(new TextComponent("[VaultMapper Debug] God Altar: " + feature.displayText), player.getUUID());
                    currentRoomGodAltarAnnouncedLines.add(debugKey);
                }
            }
        }
    }

    private static void processBrazierFeature(CompoundTag nbt, BlockPos worldPos, Player player) {
        String modifiers = parseBrazierModifiers(nbt);
        if (modifiers != null && !modifiers.isEmpty()) {
            currentRoomBrazierModifiersText = modifiers;
            currentRoomBrazierPos = worldPos;
            currentRoomBrazierMatchIndex = findMatchingBrazierTargetIndex(nbt.getCompound("Modifiers"));
            persistCurrentRoomSpecialDetections();
            if (VaultMap.debug && !modifiers.equals(currentRoomBrazierAnnouncedText)) {
                player.sendMessage(new TextComponent("[VaultMapper Debug] Brazier modifiers: " + modifiers), player.getUUID());
                currentRoomBrazierAnnouncedText = modifiers;
            }
        }
    }

    private static void processGodAltarFeature(CompoundTag nbt, BlockState blockState, BlockPos worldPos, Player player) {
        String godName = extractGodName(blockState, nbt);
        String title = extractGodChallengeTitle(nbt);
        String line = godName + ": " + title;
        upsertGodAltarPoint(currentRoomGodAltars, worldPos, line);
        persistCurrentRoomSpecialDetections();
        String debugKey = line + "@" + worldPos.getX() + "," + worldPos.getY() + "," + worldPos.getZ();
        if (VaultMap.debug && !currentRoomGodAltarAnnouncedLines.contains(debugKey)) {
            player.sendMessage(new TextComponent("[VaultMapper Debug] God Altar: " + line), player.getUUID());
            currentRoomGodAltarAnnouncedLines.add(debugKey);
        }
    }

    public static void tickCurrentRoomSpecialScan(Player player, int roomX, int roomZ, CellType cellType) {
        if (!VaultMap.enabled || player == null) {
            persistCurrentRoomSpecialDetections();
            persistCurrentRoomSpecialScanProgress();
            clearCurrentRoomSpecialDetections();
            return;
        }
        if (!VaultDimensionUtil.isInVaultNamespace(player)) {
            persistCurrentRoomSpecialDetections();
            persistCurrentRoomSpecialScanProgress();
            clearCurrentRoomSpecialDetections();
            return;
        }

        RoomSpecialDetectionConfig detectionConfig = RoomSpecialDetectionConfigManager.getActiveConfig();
        boolean scanGod = detectionConfig != null && detectionConfig.getFeatureById("god_altar") != null && RoomSpecialScanToggleConfigManager.isEnabled("god_altar");
        boolean scanBrazier = detectionConfig != null && detectionConfig.getFeatureById("brazier") != null && RoomSpecialScanToggleConfigManager.isEnabled("brazier");
        boolean scanPylon = detectionConfig != null && detectionConfig.getFeatureById("pylon") != null && RoomSpecialScanToggleConfigManager.isEnabled("pylon");

        boolean hasEnabledSpecialFeature = false;
        if (detectionConfig != null) {
            for (RoomSpecialFeatureDefinition featureDef : detectionConfig.getEnabledFeatures()) {
                if (featureDef != null && isFeatureScanEnabled(featureDef)) {
                    hasEnabledSpecialFeature = true;
                    break;
                }
            }
        }
        if (!hasEnabledSpecialFeature) {
            persistCurrentRoomSpecialDetections();
            persistCurrentRoomSpecialScanProgress();
            clearCurrentRoomSpecialDetections();
            return;
        }
        if (cellType != CellType.CELLTYPE_ROOM) {
            persistCurrentRoomSpecialDetections();
            persistCurrentRoomSpecialScanProgress();
            clearCurrentRoomSpecialDetections();
            return;
        }

        CellCoordinate roomCoordinate = new CellCoordinate(roomX, roomZ);
        if (shouldSkipSpecialRoomScan(roomCoordinate)) {
            persistCurrentRoomSpecialDetections();
            persistCurrentRoomSpecialScanProgress();
            clearCurrentRoomSpecialDetections();
            return;
        }

        if (currentRoomSpecialScanCoord == null
                || currentRoomSpecialScanCoord.x() != roomCoordinate.x()
                || currentRoomSpecialScanCoord.z() != roomCoordinate.z()
                || currentRoomSpecialScanGodEnabled != scanGod
                || currentRoomSpecialScanBrazierEnabled != scanBrazier
                || currentRoomSpecialScanPylonEnabled != scanPylon) {
            startCurrentRoomSpecialScan(roomCoordinate, (int) Math.floor(player.getY()), player, scanGod, scanBrazier, scanPylon);
        }
        if (currentRoomSpecialScanDone || currentRoomSpecialScanCoord == null) return;

        int ySpan = currentRoomSpecialScanMaxY - currentRoomSpecialScanMinY + 1;
        int totalChecks = getRoomSpecialTotalChecks(currentRoomSpecialScanMinY, currentRoomSpecialScanMaxY);
        int checks = 0;

        while (checks < ROOM_SPECIAL_SCAN_BUDGET_PER_TICK && currentRoomSpecialScanCursor < totalChecks) {
            int index = currentRoomSpecialScanCursor++;
            checks++;
            int xStride = 47 * ySpan;
            int relX = index / xStride;
            int rem = index % xStride;
            int relZ = rem / ySpan;
            int y = currentRoomSpecialScanMinY + (rem % ySpan);

            Block block = VaultMap.getCellBlock(currentRoomSpecialScanCoord.x(), currentRoomSpecialScanCoord.z(), relX, y, relZ);
            if (block == null || block.getRegistryName() == null) continue;

            String blockId = block.getRegistryName().toString();
            int worldX = currentRoomSpecialScanCoord.x() * 47 + relX;
            int worldZ = currentRoomSpecialScanCoord.z() * 47 + relZ;
            BlockPos worldPos = new BlockPos(worldX, y, worldZ);

            if (detectionConfig != null) {
                for (RoomSpecialFeatureDefinition featureDef : detectionConfig.getEnabledFeatures()) {
                    if (featureDef == null || !blockId.equals(featureDef.blockId)) continue;
                    if (!isFeatureScanEnabled(featureDef)) continue;
                    if (isFeatureScanCompleted(featureDef)) continue;
                    BlockState blockState = player.level.getBlockState(worldPos);
                    BlockEntity blockEntity = player.level.getBlockEntity(worldPos);
                    CompoundTag nbt = blockEntity != null ? blockEntity.serializeNBT() : null;
                    processDetectedFeature(featureDef, worldPos, nbt, blockState, player);
                }
            }
            if (isAllFeaturesScanCompleted(detectionConfig, scanGod, scanBrazier)) {
                currentRoomSpecialScanDone = true;
                break;
            }
        }

        if (currentRoomSpecialScanCursor >= totalChecks) currentRoomSpecialScanDone = true;

        if (currentRoomSpecialScanDone) {
            persistCurrentRoomSpecialDetections();
            roomSpecialScanProgressCache.remove(currentRoomSpecialScanCoord);
        } else {
            persistCurrentRoomSpecialScanProgress();
        }
    }
}
