package com.nodiumhosting.vaultmapper.map;

import com.nodiumhosting.vaultmapper.VaultMapper;
import com.nodiumhosting.vaultmapper.config.ClientConfig;
import com.nodiumhosting.vaultmapper.map.snapshots.MapCache;
import com.nodiumhosting.vaultmapper.network.sync.SyncClient;
import com.nodiumhosting.vaultmapper.proto.CellType;
import com.nodiumhosting.vaultmapper.proto.RoomType;
import com.nodiumhosting.vaultmapper.util.CellCoordinate;
import iskallia.vault.core.vault.ClientVaults;
import iskallia.vault.core.vault.Vault;
import iskallia.vault.core.vault.stat.DiscoveredRoomStat;
import iskallia.vault.core.vault.stat.StatCollector;
import iskallia.vault.core.vault.stat.StatsCollector;
import iskallia.vault.init.ModBlocks;
import iskallia.vault.init.ModConfigs;
import net.minecraft.client.Minecraft;
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
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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
    static int defaultMapSize = 10; // map size in cells
    static int northSize = defaultMapSize;
    static int eastSize = defaultMapSize;
    static int southSize = defaultMapSize;
    static int westSize = defaultMapSize;
    static CompoundTag hologramData;
    static boolean hologramChecked;
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
            syncClient.sendCellPacket(cell);
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
        hologramChecked = false;
        hologramData = null;

        northSize = defaultMapSize;
        eastSize = defaultMapSize;
        southSize = defaultMapSize;
        westSize = defaultMapSize;
    }

    public static VaultCell getCurrentCell() {
        return currentRoom;
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

    public static String getCellColor(VaultCell cell) {
        if (cell.roomType == RoomType.ROOMTYPE_START) {
            return ClientConfig.START_ROOM_COLOR.get();
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
        if (cell.x > eastSize){
            eastSize = cell.x;
        }
        if (cell.x < 0 && abs(cell.x) > westSize){
            westSize = abs(cell.x);
        }
        if (cell.z > southSize){
            southSize = cell.z;
        }
        if (cell.z < 0 && abs(cell.z) > northSize){
            northSize = abs(cell.z);
        }
        VaultMapOverlayRenderer.updateAnchor();
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

        VaultCell newCell;
        newCell = cellCache.get(coord);
        if (newCell == null)
            newCell = new VaultCell(playerRoomX, playerRoomZ, cellType, RoomType.ROOMTYPE_BASIC); // update current roomv
        currentRoom = newCell;
        newCell.setExplored(true);

        if (playerRoomX == 0 && playerRoomZ == 0) {
            newCell.roomType = RoomType.ROOMTYPE_START;
        } else if (cellType == CellType.CELLTYPE_ROOM && !cellCache.containsKey(coord)) {
            newCell.roomName = ClientVaults.ACTIVE.get(Vault.STATS).get(player.getUUID()).get(StatCollector.ROOMS_DISCOVERED).get(pos).toString();
            if (newCell.roomName.contains("omega")) {
                newCell.roomType = RoomType.ROOMTYPE_OMEGA;
            } else if (newCell.roomName.contains("challenge")) {
                newCell.roomType = RoomType.ROOMTYPE_CHALLENGE;
            } else if (newCell.roomName.contains("raw")) {
                newCell.roomType = RoomType.ROOMTYPE_RESOURCE;
            } else if (newCell.roomName.contains("/ore")) {
                newCell.roomType = RoomType.ROOMTYPE_ORE;
            }
        }
        if (syncClient != null) {
            syncClient.sendCellPacket(newCell);
        }
        addOrReplaceCell(newCell);
        MapCache.updateCache();
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void eventHandler(MovementInputUpdateEvent event) {
        if (!enabled) return;

        Player player = Minecraft.getInstance().player;
        if (player == null) return;

        if (!hologramChecked) {
            if (player.level.isLoaded(player.getOnPos())) {
                if (hologramData == null && player.getLevel().dimension().location().getNamespace().equals("the_vault")) {
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

        if (!player.getLevel().dimension().location().getNamespace().equals("the_vault") || !VaultMapper.isVaultDimension(player.getLevel().dimension().location().getPath())) {
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

        if (syncClient != null) syncClient.sendCellPacket(currentRoom);
    }

    public static void toggleRendering() {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;

        if (!player.getLevel().dimension().location().getNamespace().equals("the_vault") || !VaultMapper.isVaultDimension(player.getLevel().dimension().location().getPath())) {
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

            if (syncClient != null) syncClient.sendCellPacket(newCell);
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
        if (!player.getLevel().dimension().location().getNamespace().equals("the_vault")) return null;

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
