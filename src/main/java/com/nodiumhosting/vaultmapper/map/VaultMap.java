package com.nodiumhosting.vaultmapper.map;

import com.nodiumhosting.vaultmapper.VaultMapper;
import com.nodiumhosting.vaultmapper.config.ClientConfig;
import com.nodiumhosting.vaultmapper.config.RoomSignatureConfig;
import com.nodiumhosting.vaultmapper.config.RoomSignatureConfigManager;
import com.nodiumhosting.vaultmapper.map.special.RoomSpecialFeatureCoordinator;
import com.nodiumhosting.vaultmapper.map.snapshots.MapCache;
import com.nodiumhosting.vaultmapper.network.sync.SyncClient;
import com.nodiumhosting.vaultmapper.proto.CellType;
import com.nodiumhosting.vaultmapper.proto.RoomType;
import com.nodiumhosting.vaultmapper.util.CellCoordinate;
import com.nodiumhosting.vaultmapper.util.VaultDimensionUtil;
import iskallia.vault.core.vault.ClientVaults;
import iskallia.vault.core.vault.Vault;
import iskallia.vault.core.vault.stat.DiscoveredRoomStat;
import iskallia.vault.core.vault.stat.StatCollector;
import iskallia.vault.core.vault.stat.StatsCollector;
import iskallia.vault.init.ModBlocks;
import iskallia.vault.init.ModConfigs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import vazkii.quark.base.module.config.Config;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static java.lang.Math.abs;

@Mod.EventBusSubscriber({Dist.CLIENT})
public class VaultMap {
    public static String viewerCode = "";
    public static boolean enabled;
    public static boolean debug;
    public static SyncClient syncClient;
    public static ConcurrentHashMap<String, MapPlayer> players = new ConcurrentHashMap<>();
    public static CopyOnWriteArrayList<VaultCell> cells = new CopyOnWriteArrayList<>();
    public static ConcurrentHashMap<CellCoordinate, VaultCell> cellCache = new ConcurrentHashMap<>();
    static VaultCell startRoom = new VaultCell(0, 0, CellType.CELLTYPE_ROOM, RoomType.ROOMTYPE_START);
    static VaultCell currentRoom; // might not be needed
    static VaultCell currentHighlightedRoom;
    static int defaultMapSize = 10; // map size in cells
    static int northSize = defaultMapSize;
    static int eastSize = defaultMapSize;
    static int southSize = defaultMapSize;
    static int westSize = defaultMapSize;
    static CompoundTag hologramData;
    static boolean hologramChecked;
    private static int discoveredRoomSizeCache = 1;
    private static int clientTickCount = 0;
    private static final int LOADED_ROOM_SCAN_INTERVAL_TICKS = 20 * 1; // every 1 second
    private static int lastScanRoomX = Integer.MIN_VALUE;
    private static int lastScanRoomZ = Integer.MIN_VALUE;
    private static int lastScanChunkDistance = -1;
    private static boolean forceLoadedRoomScan = true;
    private static final ConcurrentHashMap<CellCoordinate, Boolean> roomSignatureCheckedCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<CellCoordinate, String> bossTypeLetterCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<CellCoordinate, Long> bossTypeProbeAtMs = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<CellCoordinate, String> laboratoryRewardLetterCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<CellCoordinate, Long> laboratoryProbeAtMs = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<CellCoordinate, String> villageTypeLetterCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<CellCoordinate, Long> villageTypeProbeAtMs = new ConcurrentHashMap<>();
    private static boolean cacheWriteRequested = false;
    private static long lastCacheWriteAtMs = 0L;
    private static final long CACHE_WRITE_COOLDOWN_MS = 750L;
    private static final long NBT_PROBE_COOLDOWN_MS = 750L;
    private static Direction startRoomTunnelDirection = Direction.NORTH;
    private static boolean startRoomOrientationResolved = false;
    private static boolean quickMapHiddenInVault = false;
    // TODO: do this properly
    private static float oldYaw;
    private static int oldRoomX;
    private static int oldRoomZ;

    public static void updatePlayerMapData(String uuid, String color, int x, int y, float yaw) {
        // uuid equals might solve the sticky ghost arrow
        if (!players.containsKey(uuid) && !uuid.equals(Objects.requireNonNull(Minecraft.getInstance().player).getStringUUID())) {
            players.put(uuid, new MapPlayer());
        }
        MapPlayer player = players.get(uuid);
        player.uuid = uuid;
        player.color = color;
        player.x = x;
        player.y = y;
        player.yaw = yaw;
    }

    public static void removePlayerMapData(String uuid) {
        players.remove(uuid);
    }

    public static void clearPlayers() {
        players.clear();
    }

    private static VaultCell getCellForSync(VaultCell cell) {
        if (cell == null) {
            return null;
        }

        if (cell.cellType == CellType.CELLTYPE_ROOM && !cell.explored && !cell.inscripted && !cell.marked) {
            VaultCell sanitized = new VaultCell(cell.x, cell.z, cell.cellType, RoomType.ROOMTYPE_BASIC);
            sanitized.roomName = "";
            sanitized.explored = false;
            sanitized.inscripted = cell.inscripted;
            sanitized.marked = cell.marked;
            return sanitized;
        }

        return cell;
    }

    public static void startSync(String playerUUID, String dimName) {
        if (!ClientConfig.SYNC_ENABLED.get()) return;
        if (syncClient != null) {
            clearPlayers();
            syncClient.closeGracefully();
            syncClient = null;
        }
        VaultMap.viewerCode = "";
        syncClient = new SyncClient(playerUUID, dimName);
        syncClient.connect();

        for (VaultCell cell : cellCache.values()) {
            VaultCell outbound = getCellForSync(cell);
            if (outbound != null) {
                syncClient.sendCellPacket(outbound);
            }
        }
    }

    public static void stopSync() {
        if (syncClient != null) {
            clearPlayers();
            syncClient.closeGracefully();
            syncClient = null;
        }
    }

    public static List<VaultCell> getCells() {
        return cells;
    }

    public static void refreshCache() {
        cellCache.clear();
        for (VaultCell cell : cells) {
            cellCache.put(new CellCoordinate(cell.x, cell.z), cell);
        }
    }

    public static void resetMap() {
        cells = new CopyOnWriteArrayList<>();
        cellCache = new ConcurrentHashMap<>();
        startRoom = new VaultCell(0, 0, CellType.CELLTYPE_ROOM, RoomType.ROOMTYPE_START);
        currentRoom = null;
        currentHighlightedRoom = null;
        hologramChecked = false;
        hologramData = null;
        forceLoadedRoomScan = true;
        lastScanRoomX = Integer.MIN_VALUE;
        lastScanRoomZ = Integer.MIN_VALUE;
        lastScanChunkDistance = -1;
        roomSignatureCheckedCache.clear();
        bossTypeLetterCache.clear();
        bossTypeProbeAtMs.clear();
        laboratoryRewardLetterCache.clear();
        laboratoryProbeAtMs.clear();
        villageTypeLetterCache.clear();
        villageTypeProbeAtMs.clear();
        cacheWriteRequested = false;
        lastCacheWriteAtMs = 0L;
        startRoomTunnelDirection = Direction.NORTH;
        startRoomOrientationResolved = false;
        quickMapHiddenInVault = false;
        RoomSpecialFeatureCoordinator.invalidateRoomSpecialDetections();

        northSize = defaultMapSize;
        eastSize = defaultMapSize;
        southSize = defaultMapSize;
        westSize = defaultMapSize;
    }

    public static VaultCell getCurrentCell() {
        return currentRoom;
    }

    private static void requestCacheWrite() {
        cacheWriteRequested = true;
    }

    private static void flushRequestedCacheWrite() {
        if (!cacheWriteRequested) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCacheWriteAtMs < CACHE_WRITE_COOLDOWN_MS) {
            return;
        }
        MapCache.updateCache();
        cacheWriteRequested = false;
        lastCacheWriteAtMs = now;
    }

    public static int getMapRotationQuarterTurns() {
        if (!startRoomOrientationResolved) {
            return 0;
        }

        return switch (startRoomTunnelDirection) {
            case NORTH -> 2;
            case EAST -> 1;
            case SOUTH -> 0;
            case WEST -> 3;
            default -> 2;
        };
    }

    public static VaultCell getCurrentHighlightedRoom() {
        if (currentHighlightedRoom != null) {
            return currentHighlightedRoom;
        }
        if (currentRoom != null && currentRoom.cellType == CellType.CELLTYPE_ROOM) {
            return currentRoom;
        }
        return null;
    }

    public static void invalidateRoomSignatureChecks() {
        roomSignatureCheckedCache.clear();
        forceLoadedRoomScan = true;

        boolean changed = false;
        for (VaultCell cell : cells) {
            if (cell.cellType == CellType.CELLTYPE_ROOM && !cell.explored && !cell.inscripted) {
                if (cell.roomType != RoomType.ROOMTYPE_BASIC || (cell.roomName != null && !cell.roomName.isEmpty())) {
                    cell.roomType = RoomType.ROOMTYPE_BASIC;
                    cell.roomName = "";
                    changed = true;
                    if (syncClient != null) {
                        syncClient.sendCellPacket(getCellForSync(cell));
                    }
                }
            }
        }

        if (changed) {
            requestCacheWrite();
        }

        scanLoadedRoomsAroundPlayer();
    }

    private static boolean isCurrentRoom(CellCoordinate coord) {
        return currentRoom != null && currentRoom.x == coord.x() && currentRoom.z == coord.z();
    }

    private static CellType getCellType(int x, int z) {
        if (x == 0 && z == 0) {
            return CellType.CELLTYPE_ROOM;
        }

        // clock wise
        Block n = VaultMap.getCellBlock(x, z, 23, 33, 42);
        Block e = VaultMap.getCellBlock(x, z, 42, 33, 23);
        Block s = VaultMap.getCellBlock(x, z, 23, 33, 4);
        Block w = VaultMap.getCellBlock(x, z, 4, 33, 23);

        if (n == null || e == null || s == null || w == null) {
            return null; // not all blocks are loaded - retry later
        }

        boolean nBed = n == ModBlocks.VAULT_BEDROCK;
        boolean eBed = e == ModBlocks.VAULT_BEDROCK;
        boolean sBed = s == ModBlocks.VAULT_BEDROCK;
        boolean wBed = w == ModBlocks.VAULT_BEDROCK;

        if (nBed && eBed && sBed && wBed) {
            return CellType.CELLTYPE_UNKNOWN;
        }
        if (nBed && !eBed && sBed && !wBed) {
            return CellType.CELLTYPE_TUNNEL_X;
        }
        if (!nBed && eBed && !sBed && wBed) {
            return CellType.CELLTYPE_TUNNEL_Z;
        }
        if (!nBed && !eBed && !sBed && !wBed) {
            return CellType.CELLTYPE_ROOM;
        }
        // can happen if vm receives old data from chunkcache (for example from overworld) - retry later
        return null;
    }

    private static String getCellBlockIdString(int cellX, int cellZ, int relativeX, int y, int relativeZ) {
        Block block = getCellBlock(cellX, cellZ, relativeX, y, relativeZ);
        if (block == null || block.getRegistryName() == null) {
            return null;
        }
        return block.getRegistryName().toString();
    }

    private static boolean isBossRoom(VaultCell cell) {
        return cell != null && cell.roomName != null && cell.roomName.toLowerCase().contains("boss");
    }

    private static boolean isLaboratoryRoom(VaultCell cell) {
        if (cell == null || cell.roomName == null) {
            return false;
        }
        String lower = cell.roomName.toLowerCase();
        return lower.contains("challenge/laboratory") || lower.contains("challenge/laboratory2");
    }

    private static boolean isVillageRoom(VaultCell cell) {
        if (cell == null || cell.roomType != RoomType.ROOMTYPE_CHALLENGE || cell.roomName == null) {
            return false;
        }
        String lower = cell.roomName.toLowerCase();
        return lower.contains("challenge/village") || lower.contains("villagefort");
    }

    private static boolean isVillageFortRoom(VaultCell cell) {
        if (cell == null || cell.roomName == null) {
            return false;
        }
        String lower = cell.roomName.toLowerCase();
        return lower.contains("villagefort");
    }

    private static String parseBossTypeLetterFromId(String bossId) {
        if (bossId == null || bossId.isEmpty()) {
            return null;
        }

        String lowerId = bossId.toLowerCase();
        if (lowerId.contains("boogieman")) {
            return "B";
        }
        if (lowerId.contains("black_widow") || lowerId.contains("spider")) {
            return "S";
        }
        if (lowerId.contains("golem")) {
            return "G";
        }
        return null;
    }

    private static String detectBossTypeLetterFromRunePillarNbt(VaultCell cell) {
        if (!isBossRoom(cell)) {
            return null;
        }

        Player player = Minecraft.getInstance().player;
        if (!VaultDimensionUtil.isInVaultNamespace(player)) {
            return null;
        }

        int[][] possiblePositions = new int[][] {
                {23, 27, 23},
                {23, 29, 23}
        };

        for (int[] position : possiblePositions) {
            int relativeX = position[0];
            int y = position[1];
            int relativeZ = position[2];

            String blockId = getCellBlockIdString(cell.x, cell.z, relativeX, y, relativeZ);
            if (!"the_vault:rune_pillar".equals(blockId)) {
                continue;
            }

            int worldX = cell.x * 47 + relativeX;
            int worldZ = cell.z * 47 + relativeZ;
            BlockPos pillarPos = new BlockPos(worldX, y, worldZ);
            BlockEntity blockEntity = player.level.getBlockEntity(pillarPos);
            if (blockEntity == null) {
                continue;
            }

            CompoundTag nbt = blockEntity.serializeNBT();
            CompoundTag bossTag = nbt.getCompound("boss");
            CompoundTag bossNbt = bossTag.getCompound("nbt");
            String bossId = bossNbt.getString("id");

            String letter = parseBossTypeLetterFromId(bossId);
            if (letter != null) {
                return letter;
            }
        }

        return null;
    }

    public static String getBossTypeLetter(VaultCell cell) {
        if (!isBossRoom(cell)) {
            return null;
        }

        CellCoordinate coordinate = new CellCoordinate(cell.x, cell.z);
        String cached = bossTypeLetterCache.get(coordinate);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        long now = System.currentTimeMillis();
        Long lastProbe = bossTypeProbeAtMs.get(coordinate);
        if (lastProbe != null && now - lastProbe < NBT_PROBE_COOLDOWN_MS) {
            return null;
        }
        bossTypeProbeAtMs.put(coordinate, now);

        String detected = detectBossTypeLetterFromRunePillarNbt(cell);
        if (detected != null && !detected.isEmpty()) {
            bossTypeLetterCache.put(coordinate, detected);
            bossTypeProbeAtMs.remove(coordinate);
        }

        return detected;
    }

    private static String detectLaboratoryRewardLetterFromProxyNbt(VaultCell cell) {
        if (!isLaboratoryRoom(cell)) {
            return null;
        }

        Player player = Minecraft.getInstance().player;
        if (!VaultDimensionUtil.isInVaultNamespace(player)) {
            return null;
        }

        int[][] possiblePositions = new int[][] {
                {20, 29, 23},
                {23, 29, 20},
                {23, 29, 26},
                {26, 29, 23}
        };

        boolean foundFocus = false;

        for (int[] position : possiblePositions) {
            int relativeX = position[0];
            int y = position[1];
            int relativeZ = position[2];

            String blockId = getCellBlockIdString(cell.x, cell.z, relativeX, y, relativeZ);
            if (!"the_vault:elite_controller_proxy".equals(blockId)) {
                continue;
            }

            int worldX = cell.x * 47 + relativeX;
            int worldZ = cell.z * 47 + relativeZ;
            BlockPos proxyPos = new BlockPos(worldX, y, worldZ);
            BlockEntity blockEntity = player.level.getBlockEntity(proxyPos);
            if (blockEntity == null) {
                continue;
            }

            CompoundTag nbt = blockEntity.serializeNBT();
            ListTag actions = nbt.getList("actions", Tag.TAG_COMPOUND);
            for (int i = 0; i < actions.size(); i++) {
                CompoundTag action = actions.getCompound(i);
                String type = action.getString("type");
                if (!"floating_item_reward".equals(type)) {
                    continue;
                }

                CompoundTag config = action.getCompound("config");
                int textColor = config.contains("textColor") ? config.getInt("textColor") : 0;
                CompoundTag item = config.getCompound("item");
                String itemId = item.getString("id");
                CompoundTag itemTag = item.getCompound("tag");

                boolean isUnique = false;
                if (!itemTag.isEmpty()) {
                    String rollType = itemTag.getString("the_vault:gear_roll_type");
                    if ("Unique".equalsIgnoreCase(rollType) || itemTag.contains("the_vault:gear_unique_pool")) {
                        isUnique = true;
                    }
                }
                if (textColor == -1213660) {
                    isUnique = true;
                }

                if (isUnique) {
                    return "U";
                }

                boolean isFocus = (itemId != null && itemId.endsWith("_focus")) || textColor == -19456;
                if (isFocus) {
                    foundFocus = true;
                }
            }
        }

        if (foundFocus) {
            return "F";
        }

        return null;
    }

    public static String getLaboratoryRewardLetter(VaultCell cell) {
        if (!isLaboratoryRoom(cell)) {
            return null;
        }

        CellCoordinate coordinate = new CellCoordinate(cell.x, cell.z);
        String cached = laboratoryRewardLetterCache.get(coordinate);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        long now = System.currentTimeMillis();
        Long lastProbe = laboratoryProbeAtMs.get(coordinate);
        if (lastProbe != null && now - lastProbe < NBT_PROBE_COOLDOWN_MS) {
            return null;
        }
        laboratoryProbeAtMs.put(coordinate, now);

        String detected = detectLaboratoryRewardLetterFromProxyNbt(cell);
        if (detected != null && !detected.isEmpty()) {
            laboratoryRewardLetterCache.put(coordinate, detected);
            laboratoryProbeAtMs.remove(coordinate);
        }

        return detected;
    }

    private static String detectVillageTypeLetterFromBlock(VaultCell cell) {
        if (!isVillageRoom(cell)) {
            return null;
        }

        Player player = Minecraft.getInstance().player;
        if (!VaultDimensionUtil.isInVaultNamespace(player)) {
            return null;
        }

        if (isVillageFortRoom(cell)) {
            return "W";
        }

        String blockId = getCellBlockIdString(cell.x, cell.z, 23, 55, 23);
        if (blockId == null || blockId.isEmpty()) {
            return null;
        }

        return switch (blockId) {
            case "minecraft:light_blue_terracotta" -> "O";
            case "minecraft:black_concrete" -> "G";
            case "auxiliaryblocks:blue_pastel" -> "L";
            default -> null;
        };
    }

    public static String getVillageTypeLetter(VaultCell cell) {
        if (!isVillageRoom(cell)) {
            return null;
        }

        CellCoordinate coordinate = new CellCoordinate(cell.x, cell.z);
        String cached = villageTypeLetterCache.get(coordinate);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        long now = System.currentTimeMillis();
        Long lastProbe = villageTypeProbeAtMs.get(coordinate);
        if (lastProbe != null && now - lastProbe < NBT_PROBE_COOLDOWN_MS) {
            return null;
        }
        villageTypeProbeAtMs.put(coordinate, now);

        String detected = detectVillageTypeLetterFromBlock(cell);
        if (detected != null && !detected.isEmpty()) {
            villageTypeLetterCache.put(coordinate, detected);
            villageTypeProbeAtMs.remove(coordinate);
        }

        return detected;
    }

    public static String getCurrentRoomBrazierOverlayText() {
        return RoomSpecialFeatureCoordinator.getCurrentRoomBrazierOverlayText();
    }

    public static String getCurrentRoomCakeOverlayText() {
        return RoomSpecialFeatureCoordinator.getCurrentRoomCakeOverlayText();
    }

    public static List<String> getCurrentRoomPylonOverlayLines() {
        return RoomSpecialFeatureCoordinator.getCurrentRoomPylonOverlayLines();
    }

    public static boolean currentRoomBrazierMatchesTarget() {
        return RoomSpecialFeatureCoordinator.currentRoomBrazierMatchesTarget();
    }

    public static Integer getRoomBrazierMatchIndex(VaultCell cell) {
        if (cell == null || cell.cellType != CellType.CELLTYPE_ROOM) return null;
        return RoomSpecialFeatureCoordinator.getRoomBrazierMatchIndex(new CellCoordinate(cell.x, cell.z));
    }

    public static String getRoomBrazierMatchIdentifier(VaultCell cell) {
        if (cell == null || cell.cellType != CellType.CELLTYPE_ROOM) return null;
        return RoomSpecialFeatureCoordinator.getRoomBrazierMatchIdentifier(new CellCoordinate(cell.x, cell.z));
    }

    public static String getRoomCenterIndicatorLetter(VaultCell cell) {
        if (cell == null || cell.cellType != CellType.CELLTYPE_ROOM) return null;

        String roomName = cell.roomName == null ? "" : cell.roomName;
        String lower = roomName.toLowerCase();

        if (lower.contains("boss")) {
            String letter = getBossTypeLetter(cell);
            if (letter != null && !letter.isEmpty()) return letter;
        }

        if (lower.contains("challenge/laboratory") || lower.contains("challenge/laboratory2")) {
            String letter = getLaboratoryRewardLetter(cell);
            if (letter != null && !letter.isEmpty()) return letter;
        }

        if (cell.roomType == RoomType.ROOMTYPE_CHALLENGE && (lower.contains("challenge/village") || lower.contains("villagefort"))) {
            String letter = getVillageTypeLetter(cell);
            if (letter != null && !letter.isEmpty()) return letter;
        }

        String brazierIdentifier = RoomSpecialFeatureCoordinator.getRoomBrazierMatchIdentifier(new CellCoordinate(cell.x, cell.z));
        if (brazierIdentifier != null && !brazierIdentifier.isEmpty()) return brazierIdentifier;

        return null;
    }

    public static List<Integer> getRoomGodAltarIndicatorColors(VaultCell cell) {
        if (cell == null || cell.cellType != CellType.CELLTYPE_ROOM) return new ArrayList<>();
        return RoomSpecialFeatureCoordinator.getRoomGodAltarIndicatorColors(new CellCoordinate(cell.x, cell.z));
    }

    public static List<Integer> getRoomPylonIndicatorColors(VaultCell cell) {
        if (cell == null || cell.cellType != CellType.CELLTYPE_ROOM) return new ArrayList<>();
        return RoomSpecialFeatureCoordinator.getRoomPylonIndicatorColors(new CellCoordinate(cell.x, cell.z));
    }

    public static List<BlockPos> getRoomPylonPositions(VaultCell cell) {
        if (cell == null || cell.cellType != CellType.CELLTYPE_ROOM) return new ArrayList<>();
        return RoomSpecialFeatureCoordinator.getRoomPylonPositions(new CellCoordinate(cell.x, cell.z));
    }

    public static List<BlockPos> getRoomGodAltarPositions(VaultCell cell) {
        if (cell == null || cell.cellType != CellType.CELLTYPE_ROOM) return new ArrayList<>();
        return RoomSpecialFeatureCoordinator.getRoomGodAltarPositions(new CellCoordinate(cell.x, cell.z));
    }

    public static List<BlockPos> getRoomCakePositions(VaultCell cell) {
        if (cell == null || cell.cellType != CellType.CELLTYPE_ROOM) return new ArrayList<>();
        return RoomSpecialFeatureCoordinator.getRoomCakePositions(new CellCoordinate(cell.x, cell.z));
    }

    public static List<String> getRoomPylonTypes(VaultCell cell) {
        if (cell == null || cell.cellType != CellType.CELLTYPE_ROOM) return new ArrayList<>();
        return RoomSpecialFeatureCoordinator.getRoomPylonTypes(new CellCoordinate(cell.x, cell.z));
    }

    public static List<Integer> getRoomCakeIndicatorColors(VaultCell cell) {
        if (cell == null || cell.cellType != CellType.CELLTYPE_ROOM) return new ArrayList<>();
        return RoomSpecialFeatureCoordinator.getRoomCakeIndicatorColors(new CellCoordinate(cell.x, cell.z));
    }

    public static void invalidateBrazierTargetMatches() {
        RoomSpecialFeatureCoordinator.invalidateRoomSpecialDetections();
    }

    public static void invalidateRoomSpecialDetections() {
        RoomSpecialFeatureCoordinator.invalidateRoomSpecialDetections();
    }

    public static List<String> getCurrentRoomGodAltarOverlayLines() {
        return RoomSpecialFeatureCoordinator.getCurrentRoomGodAltarOverlayLines();
    }

    private static Direction detectStartPortalSide() {
        record Probe(Direction side, int x, int y, int z) {}

        Probe[] probes = new Probe[] {
                new Probe(Direction.NORTH, 23, 30, 19),
                new Probe(Direction.EAST, 27, 30, 23),
                new Probe(Direction.SOUTH, 23, 30, 27),
                new Probe(Direction.WEST, 19, 30, 23)
        };

        for (Probe probe : probes) {
            String blockId = getCellBlockIdString(0, 0, probe.x, probe.y, probe.z);
            if ("the_vault:vault_portal".equals(blockId)) {
                return probe.side;
            }
        }

        return null;
    }

    private static void resolveStartRoomOrientationIfNeeded() {
        if (startRoomOrientationResolved) {
            return;
        }

        Player player = Minecraft.getInstance().player;
        if (player == null || !player.getLevel().dimension().location().getNamespace().equals("the_vault")) {
            return;
        }

        Direction portalSide = detectStartPortalSide();
        if (portalSide != null) {
            startRoomTunnelDirection = portalSide.getOpposite();
            startRoomOrientationResolved = true;
        }
    }

    public static String getCellColor(VaultCell cell) {
        if (cell.roomType == RoomType.ROOMTYPE_START) {
            return ClientConfig.START_ROOM_COLOR.get();
        }
        if (cell.roomName != null && cell.roomName.toLowerCase().contains("boss")) {
            return "#CC4444";
        }
        if (cell.marked) {
            return ClientConfig.MARKED_ROOM_COLOR.get();
        }
        if (cell.inscripted) {
            return ClientConfig.INSCRIPTION_ROOM_COLOR.get();
        }
        if (cell.roomType == RoomType.ROOMTYPE_OMEGA) {
            return ClientConfig.OMEGA_ROOM_COLOR.get();
        }
        if (cell.roomType == RoomType.ROOMTYPE_CHALLENGE) {
            return ClientConfig.CHALLENGE_ROOM_COLOR.get();
        }
        if (cell.roomType == RoomType.ROOMTYPE_ORE) {
            return ClientConfig.ORE_ROOM_COLOR.get();
        }
        if (cell.roomType == RoomType.ROOMTYPE_RESOURCE) {
            return ClientConfig.RESOURCE_ROOM_COLOR.get();
        }
        return ClientConfig.ROOM_COLOR.get();
    }

    public static void addOrReplaceCell(VaultCell cell) {
        VaultCell old = cellCache.put(new CellCoordinate(cell.x, cell.z), cell);
        cells.remove(old);
        cells.add(cell);
        if (currentHighlightedRoom != null && currentHighlightedRoom.x == cell.x && currentHighlightedRoom.z == cell.z && cell.cellType == CellType.CELLTYPE_ROOM) {
            currentHighlightedRoom = cell;
        }
        boolean boundsChanged = false;
        if (cell.x > eastSize){
            eastSize = cell.x;
            boundsChanged = true;
        }
        if (cell.x < 0 && abs(cell.x) > westSize){
            westSize = abs(cell.x);
            boundsChanged = true;
        }
        if (cell.z > southSize){
            southSize = cell.z;
            boundsChanged = true;
        }
        if (cell.z < 0 && abs(cell.z) > northSize){
            northSize = abs(cell.z);
            boundsChanged = true;
        }
        if (boundsChanged) {
            VaultMapOverlayRenderer.updateAnchor();
        }
    }

    /**
     * Updates the map data and sends it to connected web clients (like OBS)
     */
    private static void updateMap() {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;

        int playerRoomX = (int) Math.floor(player.getX() / 47);
        int playerRoomZ = (int) Math.floor(player.getZ() / 47);
        BlockPos pos = new BlockPos(playerRoomX, 0, playerRoomZ);
        CellCoordinate coord = new CellCoordinate(playerRoomX, playerRoomZ);

        int playerRelativeX = (int) Math.abs(Math.floor(player.getX() % 47));
        int playerRelativeZ = (int) Math.abs(Math.floor(player.getZ() % 47));



        CellType cellType = getCellType(playerRoomX, playerRoomZ);
        if (cellType == null) return; // not all blocks are loaded - retry later
        else if (cellType == CellType.CELLTYPE_ROOM && (playerRoomX != 0 || playerRoomZ != 0) && !cellCache.containsKey(coord)) {
            StatsCollector stats = ClientVaults.ACTIVE.get(Vault.STATS);
            StatCollector stat = stats == null ? null : stats.get(player.getUUID());
            DiscoveredRoomStat discovered = stat == null ? null : stat.get(StatCollector.ROOMS_DISCOVERED);
            if (discovered == null || discovered.get(pos) == null) {
                // we are in a room that is not discovered yet - wait until its discovered
                return;
            }
        }

        // only update tunnel if player is actually in a tunnel to prevent dungeons and doors from being detected as tunnels
        int playerY = (int) player.getY();
        if ((playerY < 27 || playerY > 37) && (cellType == CellType.CELLTYPE_TUNNEL_X || cellType == CellType.CELLTYPE_TUNNEL_Z))
            return;
        if (cellType == CellType.CELLTYPE_TUNNEL_X && (playerRelativeZ < 18 || playerRelativeZ > 28)) return;
        if (cellType == CellType.CELLTYPE_TUNNEL_Z && (playerRelativeX < 18 || playerRelativeX > 28)) return;

        syncNewCell(coord, cellType, player, true);
        requestCacheWrite();
    }

    public static void syncNewCell(CellCoordinate coordinate, CellType cellType, Player player, boolean setCurrent) {
        VaultCell newCell;
        newCell = cellCache.get(coordinate);
        if(newCell == null) {
            newCell = new VaultCell(coordinate.x(), coordinate.z(), cellType, RoomType.ROOMTYPE_BASIC);
        }

        if(setCurrent) {
            currentRoom = newCell;
            if (newCell.cellType == CellType.CELLTYPE_ROOM) {
                currentHighlightedRoom = newCell;
            }
        }

        inferRoomTypeForCell(coordinate, newCell, player);

        if(syncClient != null) {
            syncClient.sendCellPacket(getCellForSync(newCell));
        }

        addOrReplaceCell(newCell);
    }

    public static void inferRoomTypeForCell(CellCoordinate coordinate, VaultCell cell, Player player) {
        if (coordinate.x() == 0 && coordinate.z() == 0) {
            cell.roomType = RoomType.ROOMTYPE_START;
            cell.explored = true;
            return;
        }

        if (cell.cellType != CellType.CELLTYPE_ROOM) {
            return;
        }

        StatsCollector stats = ClientVaults.ACTIVE.get(Vault.STATS);
        StatCollector stat = stats == null ? null : stats.get(player.getUUID());
        DiscoveredRoomStat discovered = stat == null ? null : stat.get(StatCollector.ROOMS_DISCOVERED);

        if (discovered == null) {
            return;
        }

        ResourceLocation discoveredRoom = discovered.get(new BlockPos(coordinate.x(), 0, coordinate.z()));
        if (discoveredRoom == null) {
            return;
        }

        cell.explored = true;
        cell.roomName = discoveredRoom.toString();
        if (cell.roomName.contains("omega")) {
            cell.roomType = RoomType.ROOMTYPE_OMEGA;
        } else if (cell.roomName.contains("challenge")) {
            cell.roomType = RoomType.ROOMTYPE_CHALLENGE;
        } else if (cell.roomName.contains("raw")) {
            cell.roomType = RoomType.ROOMTYPE_RESOURCE;
        } else if (cell.roomName.contains("/ore")) {
            cell.roomType = RoomType.ROOMTYPE_ORE;
        }

        if (isBossRoom(cell)) {
            getBossTypeLetter(cell);
        }
        if (isLaboratoryRoom(cell)) {
            getLaboratoryRewardLetter(cell);
        }

    }

    private enum RoomSignatureMatchResult {
        MATCHED,
        NOT_MATCHED,
        NOT_READY
    }

    private static Optional<String> getCellBlockId(int cellX, int cellZ, int relativeX, int y, int relativeZ) {
        Block block = getCellBlock(cellX, cellZ, relativeX, y, relativeZ);
        if (block == null || block.getRegistryName() == null) {
            return Optional.empty();
        }
        return Optional.of(block.getRegistryName().toString());
    }

    private static RoomType parseRoomType(String roomType) {
        if (roomType == null || roomType.isEmpty()) {
            return RoomType.ROOMTYPE_BASIC;
        }
        try {
            return RoomType.valueOf(roomType);
        } catch (IllegalArgumentException ignored) {
            String lower = roomType.toLowerCase();
            if (lower.contains("omega")) {
                return RoomType.ROOMTYPE_OMEGA;
            }
            if (lower.contains("challenge")) {
                return RoomType.ROOMTYPE_CHALLENGE;
            }
            if (lower.contains("resource") || lower.contains("raw")) {
                return RoomType.ROOMTYPE_RESOURCE;
            }
            if (lower.contains("ore")) {
                return RoomType.ROOMTYPE_ORE;
            }
            return RoomType.ROOMTYPE_BASIC;
        }
    }

    private static boolean isRotationalMode(String rotationMode) {
        return rotationMode != null && rotationMode.equalsIgnoreCase("rotational");
    }

    private static boolean isSignatureOrMode(String signatureMatchMode) {
        return signatureMatchMode != null && signatureMatchMode.equalsIgnoreCase("or");
    }

    private static List<RoomSignatureConfig.BlockCoordinate> getCandidatePositions(RoomSignatureConfig.RoomSignatureEntry entry, RoomSignatureConfig.BlockCoordinate base) {
        if (!isRotationalMode(entry.rotationMode)) {
            return List.of(base);
        }

        int center = 23;
        int dx = base.x - center;
        int dz = base.z - center;

        RoomSignatureConfig.BlockCoordinate r0 = new RoomSignatureConfig.BlockCoordinate(center + dx, base.y, center + dz);
        RoomSignatureConfig.BlockCoordinate r90 = new RoomSignatureConfig.BlockCoordinate(center - dz, base.y, center + dx);
        RoomSignatureConfig.BlockCoordinate r180 = new RoomSignatureConfig.BlockCoordinate(center - dx, base.y, center - dz);
        RoomSignatureConfig.BlockCoordinate r270 = new RoomSignatureConfig.BlockCoordinate(center + dz, base.y, center - dx);

        return List.of(r0, r90, r180, r270);
    }

    private static RoomSignatureMatchResult tryIdentifyRoomFromSignatures(VaultCell cell) {
        if (cell == null || cell.cellType != CellType.CELLTYPE_ROOM || cell.explored) {
            return RoomSignatureMatchResult.NOT_MATCHED;
        }

        RoomSignatureConfig config = RoomSignatureConfigManager.getActiveConfig();
        if (config == null || config.rooms == null || config.rooms.isEmpty()) {
            return RoomSignatureMatchResult.NOT_MATCHED;
        }

        boolean anySignatureNotReady = false;

        for (RoomSignatureConfig.RoomSignatureEntry entry : config.rooms) {
            if (entry == null || entry.signatures == null || entry.signatures.isEmpty()) {
                continue;
            }

            boolean orMode = isSignatureOrMode(entry.signatureMatchMode);
            int requiredSignatureCount = 0;
            int readySignatureCount = 0;
            int matchedSignatureCount = 0;

            for (RoomSignatureConfig.BlockSignature signature : entry.signatures) {
                if (signature == null || signature.blockId == null || signature.blockId.isEmpty() || signature.possiblePositions == null || signature.possiblePositions.isEmpty()) {
                    continue;
                }

                requiredSignatureCount++;

                boolean anyLoadedPosition = false;
                boolean anyMatchingPosition = false;
                for (RoomSignatureConfig.BlockCoordinate position : signature.possiblePositions) {
                    if (position == null) {
                        continue;
                    }

                    for (RoomSignatureConfig.BlockCoordinate candidate : getCandidatePositions(entry, position)) {
                        Optional<String> blockId = getCellBlockId(cell.x, cell.z, candidate.x, candidate.y, candidate.z);
                        if (blockId.isEmpty()) {
                            continue;
                        }

                        anyLoadedPosition = true;
                        if (signature.blockId.equals(blockId.get())) {
                            anyMatchingPosition = true;
                            break;
                        }
                    }

                    if (anyMatchingPosition) {
                        break;
                    }
                }

                if (!anyLoadedPosition) {
                    continue;
                }
                readySignatureCount++;

                if (anyMatchingPosition) {
                    matchedSignatureCount++;
                    if (orMode) {
                        break;
                    }
                }
            }

            if (requiredSignatureCount == 0) {
                continue;
            }

            if (readySignatureCount == 0) {
                anySignatureNotReady = true;
                continue;
            }

            boolean entryMatched;
            if (orMode) {
                entryMatched = matchedSignatureCount > 0;
            } else {
                entryMatched = matchedSignatureCount == requiredSignatureCount;
                if (!entryMatched && readySignatureCount < requiredSignatureCount) {
                    anySignatureNotReady = true;
                    continue;
                }
            }

            if (entryMatched) {
                cell.roomType = parseRoomType(entry.roomType);
                if (entry.variants != null && !entry.variants.isEmpty()) {
                    cell.roomName = entry.variants.get(0);
                } else if (entry.key != null && !entry.key.isEmpty()) {
                    cell.roomName = entry.key;
                }
                if (isBossRoom(cell)) {
                    getBossTypeLetter(cell);
                }
                if (isLaboratoryRoom(cell)) {
                    getLaboratoryRewardLetter(cell);
                }
                return RoomSignatureMatchResult.MATCHED;
            }
        }

        return anySignatureNotReady ? RoomSignatureMatchResult.NOT_READY : RoomSignatureMatchResult.NOT_MATCHED;
    }

    private static int getServerChunkRenderDistance() {
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return -1;
        }

        String[] methodCandidates = {"getServerChunkRadius", "getChunkRadius", "serverChunkRadius"};
        for (String name : methodCandidates) {
            try {
                Method method = connection.getClass().getMethod(name);
                Object value = method.invoke(connection);
                if (value instanceof Integer intValue && intValue > 0) {
                    return intValue;
                }
            } catch (Exception ignored) {
            }
        }

        String[] fieldCandidates = {"serverChunkRadius", "chunkRadius"};
        for (String name : fieldCandidates) {
            try {
                Field field = connection.getClass().getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(connection);
                if (value instanceof Integer intValue && intValue > 0) {
                    return intValue;
                }
            } catch (Exception ignored) {
            }
        }

        return -1;
    }

    private static int getClientChunkRenderDistance() {
        Object options = Minecraft.getInstance().options;
        if (options == null) {
            return 12;
        }

        String[] methodCandidates = {"getEffectiveRenderDistance", "getRenderDistance", "getClampedViewDistance"};
        for (String name : methodCandidates) {
            try {
                Method method = options.getClass().getMethod(name);
                Object value = method.invoke(options);
                if (value instanceof Integer intValue && intValue > 0) {
                    return intValue;
                }
            } catch (Exception ignored) {
            }
        }

        String[] fieldCandidates = {"renderDistance", "f_92076_"};
        for (String name : fieldCandidates) {
            try {
                Field field = options.getClass().getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(options);

                if (value instanceof Integer intValue && intValue > 0) {
                    return intValue;
                }

                if (value != null) {
                    try {
                        Method getMethod = value.getClass().getMethod("get");
                        Object optionValue = getMethod.invoke(value);
                        if (optionValue instanceof Integer intValue && intValue > 0) {
                            return intValue;
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }

        return 12;
    }

    private static int getEffectiveChunkRenderDistance() {
        int clientDistance = getClientChunkRenderDistance();
        int serverDistance = getServerChunkRenderDistance();
        if (serverDistance <= 0) {
            return clientDistance;
        }
        return Math.min(clientDistance, serverDistance);
    }

    private static void scanLoadedRoomsAroundPlayer() {
        if (!enabled || !ClientConfig.SCAN_LOADED_ROOMS.get()) {
            return;
        }

        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        if (!VaultDimensionUtil.isInVaultNamespace(player)) {
            return;
        }

        int playerRoomX = (int) Math.floor(player.getX() / 47);
        int playerRoomZ = (int) Math.floor(player.getZ() / 47);
        int chunkRenderDistance = getEffectiveChunkRenderDistance();

        if (!forceLoadedRoomScan
                && playerRoomX == lastScanRoomX
                && playerRoomZ == lastScanRoomZ
                && chunkRenderDistance == lastScanChunkDistance) {
            return;
        }

        lastScanRoomX = playerRoomX;
        lastScanRoomZ = playerRoomZ;
        lastScanChunkDistance = chunkRenderDistance;
        forceLoadedRoomScan = false;

        int blockRadius = chunkRenderDistance * 16;
        int extraCellRadius = Math.max(0, ClientConfig.IDENTIFICATION_EXTRA_CELL_RADIUS.get());
        int cellRadius = Math.max(1, (int) Math.ceil((blockRadius + 23) / 47.0)) + extraCellRadius;

        boolean changed = false;
        for (int dx = -cellRadius; dx <= cellRadius; dx++) {
            for (int dz = -cellRadius; dz <= cellRadius; dz++) {
                int roomX = playerRoomX + dx;
                int roomZ = playerRoomZ + dz;
                CellCoordinate coordinate = new CellCoordinate(roomX, roomZ);

                if (getCellBlock(roomX, roomZ, 23, 33, 23) == null) {
                    continue;
                }

                VaultCell cell = cellCache.get(coordinate);
                if (cell == null) {
                    CellType cellType = getCellType(roomX, roomZ);
                    if (cellType != CellType.CELLTYPE_ROOM) {
                        continue;
                    }

                    syncNewCell(coordinate, cellType, player, false);
                    changed = true;
                    cell = cellCache.get(coordinate);
                }

                if (cell == null || cell.cellType != CellType.CELLTYPE_ROOM || cell.explored || cell.inscripted) {
                    roomSignatureCheckedCache.put(coordinate, true);
                    continue;
                }

                if (roomSignatureCheckedCache.containsKey(coordinate)) {
                    continue;
                }

                RoomSignatureMatchResult signatureMatchResult = tryIdentifyRoomFromSignatures(cell);
                if (signatureMatchResult == RoomSignatureMatchResult.MATCHED) {
                    addOrReplaceCell(cell);
                    if (syncClient != null) {
                        syncClient.sendCellPacket(getCellForSync(cell));
                    }
                    roomSignatureCheckedCache.put(coordinate, true);
                    changed = true;
                } else if (signatureMatchResult == RoomSignatureMatchResult.NOT_MATCHED) {
                    roomSignatureCheckedCache.put(coordinate, true);
                }
            }
        }

        if (changed) {
            requestCacheWrite();
        }

    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void updateMapFromDiscoveryData(TickEvent.ClientTickEvent event) {
        if(event.phase.equals(TickEvent.Phase.END)) {
            resolveStartRoomOrientationIfNeeded();
            flushRequestedCacheWrite();

            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                int playerRoomX = (int) Math.floor(player.getX() / 47);
                int playerRoomZ = (int) Math.floor(player.getZ() / 47);
                CellType currentCellType = getCellType(playerRoomX, playerRoomZ);
                RoomSpecialFeatureCoordinator.tickCurrentRoomSpecialScan(player, playerRoomX, playerRoomZ, currentCellType);
            }

            clientTickCount++;
            if(clientTickCount % LOADED_ROOM_SCAN_INTERVAL_TICKS == 0) {
                scanLoadedRoomsAroundPlayer();
            }
            if(clientTickCount % 20 == 0) {
                clientTickCount = 0;
                StatsCollector stats = ClientVaults.ACTIVE.get(Vault.STATS);
                if(player == null) {
                    return;
                }

                StatCollector stat = stats == null ? null : stats.get(Minecraft.getInstance().player.getUUID());
                DiscoveredRoomStat discovered = stat == null ? null : stat.get(StatCollector.ROOMS_DISCOVERED);
                if(discovered != null) {
                    //If the size of the discovered rooms data has changed, we want to go ahead and grab any discovered rooms that are now present (from Globe activation for example)
                    if(discoveredRoomSizeCache != discovered.size()) {
                        discoveredRoomSizeCache = discovered.size();
                        discovered.forEach((blockPos, resourceLocation) -> {
                            CellCoordinate coordinate = new CellCoordinate(blockPos.getX(), blockPos.getZ());
                            VaultCell cachedCell = cellCache.get(coordinate);
                            if(cachedCell == null || !cachedCell.explored) {
                                syncNewCell(coordinate, CellType.CELLTYPE_ROOM, player, false);
                            }
                        });
                        requestCacheWrite();
                    }
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void eventHandler(MovementInputUpdateEvent event) {
        if (!enabled) return;

        Player player = Minecraft.getInstance().player;
        if (player == null) return;

        resolveStartRoomOrientationIfNeeded();

        if (!hologramChecked) {
            if (player.level.isLoaded(player.getOnPos())) {
                if (hologramData == null && VaultDimensionUtil.isInVaultNamespace(player)) {
                    hologramData = getHologramData();
                    hologramChecked = true;
                }
            }
        }

        int playerRoomX = (int) Math.floor(player.getX() / 47);
        int playerRoomZ = (int) Math.floor(player.getZ() / 47);
        CellCoordinate coord = new CellCoordinate(playerRoomX, playerRoomZ);

        float yaw = player.getYHeadRot();
        String uuid = player.getUUID().toString();

        if (syncClient != null) syncClient.sendMovePacket(uuid, playerRoomX, playerRoomZ, yaw);

        if (debug) {
            Minecraft.getInstance().gui.setOverlayMessage(new TextComponent("Current room: " + playerRoomX + ", " + playerRoomZ + " Hologram: " + (hologramData != null ? "Found" : "Not found") + (hologramChecked ? " (Checked)" : "(Not checked)") + " Vault Map Data Size: " + cells.size() + " (" + cells.stream().filter(cell -> cell.cellType == CellType.CELLTYPE_ROOM && cell.explored).count() + " Explored Rooms) + PC: " + ClientConfig.PLAYER_CENTRIC_RENDERING.get() + " " + ClientConfig.PC_CUTOFF.get() + " (config) / " + VaultMapOverlayRenderer.playerCentricRender + " " + VaultMapOverlayRenderer.cutoff + " (ram)"), false);
        }

        if (!isCurrentRoom(coord)) { // if were in a different room
            updateMap();
        }


        if (oldYaw != yaw || playerRoomX != oldRoomX || playerRoomZ != oldRoomZ) {
            oldYaw = yaw;
            oldRoomX = playerRoomX;
            oldRoomZ = playerRoomZ;
        }
    }

    public static void markCurrentCell() {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;

        if (!VaultDimensionUtil.isInVaultDimension(player)) {
            player.sendMessage(new TextComponent("You can't use this outside of Vaults"), player.getUUID());
            return;
        }

        VaultMapper.LOGGER.info("Marking room");
        if (currentRoom == null) {
            player.sendMessage(new TextComponent("No rooms available in map"), player.getUUID());
            return;
        }

        if (currentRoom.x == 0 && currentRoom.z == 0) {
            player.sendMessage(new TextComponent("You can't mark the start room"), player.getUUID());
            return;
        }

        if (getCellType(currentRoom.x, currentRoom.z) == CellType.CELLTYPE_ROOM) {
            boolean marked = currentRoom.switchMarked();
            if (marked) {
                player.sendMessage(new TextComponent("Room marked"), player.getUUID());
            } else {
                player.sendMessage(new TextComponent("Room unmarked"), player.getUUID());
            }
        } else {
            player.sendMessage(new TextComponent("You can only mark rooms"), player.getUUID());
        }

        if (syncClient != null) syncClient.sendCellPacket(getCellForSync(currentRoom));
    }

    public static void toggleRendering() {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;

        if (!VaultDimensionUtil.isInVaultDimension(player)) {
            player.sendMessage(new TextComponent("You can't use this outside of Vaults"), player.getUUID());
            return;
        }

        if (ClientConfig.MAP_ENABLED.get()) {
            ClientConfig.MAP_ENABLED.set(false);
            player.sendMessage(new TextComponent("Vault Map rendering disabled"), player.getUUID());
        } else {
            ClientConfig.MAP_ENABLED.set(true);
            player.sendMessage(new TextComponent("Vault Map rendering enabled"), player.getUUID());
        }

        ClientConfig.SPEC.save();
    }

    public static boolean isQuickMapHiddenInVault() {
        return quickMapHiddenInVault;
    }

    public static void toggleQuickMapVisibility() {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;

        if (!VaultDimensionUtil.isInVaultDimension(player)) {
            player.sendMessage(new TextComponent("You can't use this outside of Vaults"), player.getUUID());
            return;
        }

        quickMapHiddenInVault = !quickMapHiddenInVault;
        player.sendMessage(new TextComponent(quickMapHiddenInVault ? "Vault Map hidden" : "Vault Map shown"), player.getUUID());
    }

    private static CompoundTag getHologramData() {
        HashMap<BlockPos, Direction> hologramBlocks = new HashMap<>();
        hologramBlocks.put(new BlockPos(23, 27, 13), Direction.NORTH);
        hologramBlocks.put(new BlockPos(33, 27, 23), Direction.EAST);
        hologramBlocks.put(new BlockPos(13, 27, 23), Direction.WEST);
        hologramBlocks.put(new BlockPos(23, 27, 33), Direction.SOUTH);

        CompoundTag hologramNbt = null;

        // get the required data from hologram
        for (Map.Entry<BlockPos, Direction> entry : hologramBlocks.entrySet()) {
            BlockPos hologramBlockPos = entry.getKey();
            Direction direction = entry.getValue();

            BlockState hologramBlockState = Objects.requireNonNull(Objects.requireNonNull(Minecraft.getInstance().player).getLevel()).getBlockState(hologramBlockPos);
            if (!Objects.equals(hologramBlockState.getBlock().getRegistryName(), new ResourceLocation("the_vault:hologram"))) {
                continue;
            }

            BlockEntity hologramBlock = Objects.requireNonNull(Objects.requireNonNull(Minecraft.getInstance().player).getLevel()).getBlockEntity(hologramBlockPos);
            CompoundTag hologramData = Objects.requireNonNull(hologramBlock).serializeNBT();

//            if (debug) {
//                Minecraft.getInstance().player.sendMessage(new TextComponent("Hologram block: " + hologramData), UUID.randomUUID());
//            }

            // vaultDirection = direction;

            hologramNbt = hologramData;
        }

        if (hologramNbt == null) return null;

        Tag children = hologramNbt.getCompound("tree").get("children");
        ListTag childrenList = (ListTag) children;

        // extract the inscription room locations and add them to the inscription room list
        childrenList.forEach(tag -> {
            CompoundTag compound = (CompoundTag) tag;
            CompoundTag stack = compound.getCompound("stack");
            String id = stack.getString("id");
            int model = stack.getCompound("tag").getCompound("data").getInt("model");
            Tuple<RoomType, String> room = roomFromModel(model);
            CompoundTag translation = compound.getCompound("translation");
            byte translationX = translation.getByte("x");
            byte translationY = translation.getByte("y");

            int translationXInt = translationX;
            int translationYInt = translationY;

            VaultCell newCell = new VaultCell(translationXInt * 2, translationYInt * -2, CellType.CELLTYPE_ROOM, room.getA());
            newCell.roomName = room.getB();
            // TODO change this later when we do detection of room types
            newCell.inscripted = true;
            cells.add(newCell);

            if (syncClient != null) syncClient.sendCellPacket(getCellForSync(newCell));
        });

        return hologramNbt;
    }

    /**
     * Gets the block in cell on specific coordinate
     *
     * @param cellX  X coord of cell
     * @param cellZ  Z coord of cell
     * @param blockX X coord of block inside a cell
     * @param blockZ Z coord of block inside a cell
     * @return Block or null if unavailable
     */
    public static Block getCellBlock(int cellX, int cellZ, int blockX, int blockY, int blockZ) {
        Player player = Minecraft.getInstance().player;

        if (player == null) return null;
        if (!VaultDimensionUtil.isInVaultNamespace(player)) return null;

        int xCoord = cellX * 47 + blockX;
        int zCoord = cellZ * 47 + blockZ;
        //VaultMapper.LOGGER.info("X " + xCoord + " Z " + zCoord);

        if (!player.level.isLoaded(new BlockPos(xCoord, blockY, zCoord))) return null;

        return player.level.getBlockState(new BlockPos(xCoord, blockY, zCoord)).getBlock();
    }

    public static Tuple<RoomType, String> roomFromModel(int model) {
        ResourceLocation room = null;
        for (Map.Entry<ResourceLocation, Integer> entry : ModConfigs.INSCRIPTION.poolToModel.entrySet()) {
            if (entry.getValue() == model) {
                room = entry.getKey();
                break;
            }
        }
        if (room == null) {
            return new Tuple<>(RoomType.ROOMTYPE_BASIC, "");
        }
        RoomType type = RoomType.ROOMTYPE_BASIC;
        if (room.getPath().contains("omega")) {
            type = RoomType.ROOMTYPE_OMEGA;
        } else if (room.getPath().contains("challenge")) {
            type = RoomType.ROOMTYPE_CHALLENGE;
        } else if (room.getPath().contains("raw") || room.getPath().contains("resource")) {
            type = RoomType.ROOMTYPE_RESOURCE;
        }
//        String name = VaultRegistry.TEMPLATE_POOL.getKey(room).getId().toString();
        String name = room.toString(); //TODO: instead we should get an actual template from the pool
        return new Tuple<>(type, name);
    }

    static public class MapPlayer {
        public String uuid;
        public String color;
        int x;
        int y;
        float yaw;
    }
}
