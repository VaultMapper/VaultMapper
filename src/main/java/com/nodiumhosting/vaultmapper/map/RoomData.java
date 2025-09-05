package com.nodiumhosting.vaultmapper.map;

import com.nodiumhosting.vaultmapper.VaultMapper;
import com.nodiumhosting.vaultmapper.mixin.StructureTemplateAccessor;
import com.nodiumhosting.vaultmapper.proto.RoomType;
import iskallia.vault.block.CoinPileBlock;
import iskallia.vault.block.VaultChestBlock;
import iskallia.vault.block.VaultOreBlock;
import iskallia.vault.core.Version;
import iskallia.vault.core.data.key.TemplatePoolKey;
import iskallia.vault.core.vault.VaultRegistry;
import iskallia.vault.core.world.data.tile.PartialTile;
import iskallia.vault.core.world.template.StructureTemplate;
import iskallia.vault.core.world.template.Template;
import iskallia.vault.core.world.template.data.DirectTemplateEntry;
import iskallia.vault.core.world.template.data.IndirectTemplateEntry;
import iskallia.vault.core.world.template.data.TemplatePool;
import iskallia.vault.init.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Tuple;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.*;

public class RoomData {

    public static List<RoomData> omegaRooms;
    public static List<RoomData> challengeRooms;
    public static List<RoomData> resourceRooms;
    public String type;
    public String name;
    public Template room;
    public List<Map<Integer, Block>> columnList = new ArrayList<>();
    public Block mineOption1;
    public Block mineOption2;
    public Block raidOption;
    public Block runeOption;

    public RoomData() {
        this.type = "current";
        this.name = "current";
        this.room = null;
    }

    public RoomData(String type, String name, Template room) {
        this.type = type;
        this.name = name;
        this.room = room;

        VaultMapper.LOGGER.debug("processing {}", name);
//        StructureTemplateAccessor.getLAZY_LOADING_EXECUTOR().execute(() -> {
            this.columnList.clear();
            Map<Integer, Block> northeastColumn = new HashMap<>();
            Map<Integer, Block> northwestColumn = new HashMap<>();
            Map<Integer, Block> southeastColumn = new HashMap<>();
            Map<Integer, Block> southwestColumn = new HashMap<>();
            this.columnList.add(northeastColumn);
            this.columnList.add(northwestColumn);
            this.columnList.add(southeastColumn);
            this.columnList.add(southwestColumn);

            Iterator<PartialTile> tiles = room.getTiles(Template.ALL_TILES);
            while(tiles.hasNext()) {
                PartialTile tile = tiles.next();
                BlockPos pos = tile.getPos();
                Optional<Block> optBlock = tile.getState().getBlock().asWhole();
                if (optBlock.isEmpty()) {
                    continue;
                }

                Block block = optBlock.get();
                int x = pos.getX();
                int z = pos.getZ();
                int y = pos.getY();
                if (x == 0 && z == 0) {
                    northwestColumn.put(y, block);
                } else if (x == 46 && z == 0) {
                    northeastColumn.put(y, block);
                } else if (x == 0 && z == 46) {
                    southwestColumn.put(y, block);
                } else if (x == 46 && z == 46) {
                    southeastColumn.put(y, block);
                }

                if (x == 23 && y == 32 && z == 23) {
                    this.mineOption1 = block;
                } else if (x == 23 && y == 31 && z == 23) {
                    this.mineOption2 = block;
                } else if (x == 23 && y == 29 && z == 23) {
                    this.raidOption = block;
                }
            }

            if (room instanceof StructureTemplate structure) {
                ((StructureTemplateAccessor) structure).getTiles().clear();
                ((StructureTemplateAccessor) structure).getEntities().clear();
                structure.setUninitialized();
                VaultMapper.LOGGER.debug("uninitializing {}", name);
            }
//        });
    }

    public static void initRooms() {
        VaultMapper.LOGGER.debug("Room init");
        omegaRooms = new ArrayList<>();
        challengeRooms = new ArrayList<>();
        resourceRooms = new ArrayList<>();
        TemplatePoolKey challengeRef = VaultRegistry.TEMPLATE_POOL.getKey("the_vault:vault/rooms/challenge_rooms");
        TemplatePoolKey omegaRef = VaultRegistry.TEMPLATE_POOL.getKey("the_vault:vault/rooms/omega_rooms");
        TemplatePoolKey resourceRef = VaultRegistry.TEMPLATE_POOL.getKey("the_vault:vault/rooms/raw/resources_lvl30");
        TemplatePoolKey bossRef = VaultRegistry.TEMPLATE_POOL.getKey("the_vault:vault/rooms/special/boss");

        if (challengeRef == null) {
            VaultMapper.LOGGER.error("challengeRef is null");
            return;
        }
        if (omegaRef == null) {
            VaultMapper.LOGGER.error("omegaRef is null");
            return;
        }
        if (resourceRef == null) {
            VaultMapper.LOGGER.error("resourceRef is null");
            return;
        }
        if (!challengeRef.supports(Version.latest())) {
            VaultMapper.LOGGER.error("challengeRef doesnt support version");
            return;
        }
        if (!omegaRef.supports(Version.latest())) {
            VaultMapper.LOGGER.error("omegaRef doesnt support version");
            return;
        }
        if (!resourceRef.supports(Version.latest())) {
            VaultMapper.LOGGER.error("resourceRef doesnt support version");
            return;
        }
        if (!bossRef.supports(Version.latest())) {
            VaultMapper.LOGGER.error("bossRef doesnt support version");
            return;
        }
        TemplatePool challenge = challengeRef.get(Version.latest());
        TemplatePool omega = omegaRef.get(Version.latest());
        TemplatePool resource = resourceRef.get(Version.latest());
        TemplatePool boss = bossRef.get(Version.latest());

        if (!challengeRef.supports(Version.latest()))
            if (challenge == null) {
                VaultMapper.LOGGER.error("Cant find challenge rooms");
                return;
            }
        challenge.iterate((entry -> {
            if (entry instanceof IndirectTemplateEntry roomBatchRef) {
                if (!roomBatchRef.getReference().supports(Version.latest())) {
                    return true;
                }
                String name = roomBatchRef.getReference().getId().toString();
                iterateRooms(challengeRooms, "challenge", name, roomBatchRef);
            }

            return true;
        }));

        boss.iterate((entry -> {
            if (entry instanceof DirectTemplateEntry roomFileRef) {
                if (!roomFileRef.getTemplate().supports(Version.latest())) {
                    return true;
                }
                Template roomFile = roomFileRef.getTemplate().get(Version.latest());
                String name = roomFileRef.getTemplate().getId().toString();
                challengeRooms.add(new RoomData("challenge", name, roomFile));
                return true;
            }
            return true;
        }));

        omega.iterate((entry -> {
            if (entry instanceof IndirectTemplateEntry roomBatchRef) {
                if (!roomBatchRef.getReference().supports(Version.latest())) {
                    return true;
                }
                String name = roomBatchRef.getReference().getId().toString();
                iterateRooms(omegaRooms, "omega", name, roomBatchRef);
            }

            return true;
        }));
        resource.iterate((entry -> {
            if (entry instanceof IndirectTemplateEntry roomBatchRef) {
                if (!roomBatchRef.getReference().supports(Version.latest())) {
                    return true;
                }
                String name = roomBatchRef.getReference().getId().toString();
                iterateRooms(resourceRooms, "resource", name, roomBatchRef);
            }

            return true;
        }));

        TemplatePoolKey diamondRef = VaultRegistry.TEMPLATE_POOL.getKey("the_vault:vault/rooms/raw/diamond_caves");
        if (diamondRef != null && diamondRef.supports(Version.latest())) {
            TemplatePool diamond = diamondRef.get(Version.latest());
            diamond.iterate((entry -> {
                if (entry instanceof DirectTemplateEntry roomFileRef) {
                    if (!roomFileRef.getTemplate().supports(Version.latest())) {
                        return true;
                    }
                    Template roomFile = roomFileRef.getTemplate().get(Version.latest());
                    String name = roomFileRef.getTemplate().getId().toString();
                    resourceRooms.add(new RoomData("resource", name, roomFile));
                }
                return true;
            }));
        }

        // try to get hellish digsite, but silently fail if it doesn't exist - wold's might not be loaded
        TemplatePoolKey hellishRef = VaultRegistry.TEMPLATE_POOL.getKey("the_vault:vault/rooms/hellish_rooms");
        if (hellishRef != null && hellishRef.supports(Version.latest())) {
            TemplatePool hellish = hellishRef.get(Version.latest());
            hellish.iterate((entry -> {
                if (entry instanceof IndirectTemplateEntry roomBatchRef) {
                    if (!roomBatchRef.getReference().supports(Version.latest())) {
                        return true;
                    }
                    String name = roomBatchRef.getReference().getId().toString();
                    if (!name.equals("hellish_digsite")) {
                        return true;
                    }
                    iterateRooms(omegaRooms, "omega", name, roomBatchRef);
                }

                return true;
            }));
        }
        VaultMapper.LOGGER.debug("Room init done");
    }

    public static void iterateRooms(List<RoomData> listToAdd, String type, String name, IndirectTemplateEntry roomBatchRef) {
        TemplatePool roomBatch = roomBatchRef.getReference().get(Version.latest());
        roomBatch.iterate((inner) -> {
            if (inner instanceof DirectTemplateEntry roomFileRef) {
                if (!roomFileRef.getTemplate().supports(Version.latest())) {
                    return true;
                }
                Template roomFile = roomFileRef.getTemplate().get(Version.latest());
                String roomName = roomFileRef.getTemplate().getId().toString();
                //VaultMapper.LOGGER.info("Room File name: " + name);
                listToAdd.add(new RoomData(type, roomName, roomFile));
                return true;
            }
            if (inner instanceof IndirectTemplateEntry batchRef) {
                iterateRooms(listToAdd, type, name, batchRef);
            }
            return true;
        });
    }

    public static boolean compareBlock(Block block1, Block block2) {
        if (block1 == Blocks.AIR || block1 instanceof VaultChestBlock || block1 instanceof CoinPileBlock) {
            block1 = null;
        }
        if (block2 == Blocks.AIR || block2 instanceof VaultChestBlock || block2 instanceof CoinPileBlock) {
            block2 = null;
        }
        if (block1 == block2) {
            return true;
        }
        if (block2 == ModBlocks.PLACEHOLDER) {
            return block1 == Blocks.STONE || block1 == Blocks.COBBLESTONE || block1 == Blocks.ANDESITE || block1 instanceof VaultOreBlock;
        }
        if (block1 == ModBlocks.PLACEHOLDER) {
            return block2 == Blocks.STONE || block2 == Blocks.COBBLESTONE || block2 == Blocks.ANDESITE || block2 instanceof VaultOreBlock;
        }
        if (block1 == null || block2 == null) {
//            VaultMapper.LOGGER.info("Failed test on null");
            return false;
        }
        //VaultMapper.LOGGER.info("Failed on " +  block1.getDescriptionId() +" "+block2.getDescriptionId() );
        return false;
    }

    public static boolean compareColumn(Map<Integer, Block> map1, Map<Integer, Block> map2, int minYLevel) {
        Set<Integer> combinedKeySet = new HashSet<>(map1.keySet());
        combinedKeySet.addAll(map2.keySet());
        for (Integer key : combinedKeySet) {
            Block block1 = null;
            if (key < minYLevel) {
                continue;
            }
            if (map1.containsKey(key)) {
                block1 = map1.get(key);
            }
            Block block2 = null;
            if (map2.containsKey(key)) {
                block2 = map2.get(key);
            }
            if (!compareBlock(block1, block2)) {
                return false;
            }
        }
        return true;
    }

    public static boolean compareColumnPercentageRequired(Map<Integer, Block> map1, Map<Integer, Block> map2, int minYLevel, float percentage) {
        Set<Integer> combinedKeySet = new HashSet<>(map1.keySet());
        combinedKeySet.addAll(map2.keySet());
        int blockCount = combinedKeySet.size();
        int allowedMistakes = (int) Math.floor((1.0f - percentage) * blockCount);
        int currentMistakes = 0;
        for (Integer key : combinedKeySet) {
            Block block1 = null;
            if (key < minYLevel) {
                continue;
            }
            if (map1.containsKey(key)) {
                block1 = map1.get(key);
            }
            Block block2 = null;
            if (map2.containsKey(key)) {
                block2 = map2.get(key);
            }
            if (!compareBlock(block1, block2)) {
                currentMistakes++;
            }
            if (currentMistakes > allowedMistakes) {
                return false;
            }
        }
        return true;
    }
    //public Map<Integer,Block> centerColumn = new HashMap<>();

    public static RoomData captureRoom(int cellX, int cellZ) {
        RoomData currentRoom = new RoomData();
        currentRoom.columnList.add(captureColumn(cellX, cellZ, 0, 0));
        currentRoom.columnList.add(captureColumn(cellX, cellZ, 0, 46));
        currentRoom.columnList.add(captureColumn(cellX, cellZ, 46, 0));
        currentRoom.columnList.add(captureColumn(cellX, cellZ, 46, 46));
        currentRoom.mineOption1 = VaultMap.getCellBlock(cellX, cellZ, 23, 32 + 9, 23);
        currentRoom.mineOption2 = VaultMap.getCellBlock(cellX, cellZ, 23, 31 + 9, 23);
        currentRoom.raidOption = VaultMap.getCellBlock(cellX, cellZ, 23, 20 + 9, 23);
        currentRoom.runeOption = VaultMap.getCellBlock(cellX, cellZ, 23, 18 + 9, 23);
        //currentRoom.centerColumn = captureColumn(cellX,cellZ,23,23);
        return currentRoom;
    }

    public static Map<Integer, Block> captureColumn(int cellX, int cellZ, int x, int z) {
        Map<Integer, Block> column = new HashMap<>();
        for (int i = 0; i <= 46; i++) {
            Block block = VaultMap.getCellBlock(cellX, cellZ, 46, i + 9, 46);
            column.put(i, block);
        }
        return column;
    }

    public boolean compareRoom(RoomData roomData) {
        //roomData is omega/challenge
        //this is current
        int yLevel = 0;
        if (roomData.name.contains("village") || roomData.name.contains("raid_room")) {
            yLevel = 19;
        }
        if (roomData.name.contains("mine")) {
            //if (!compareColumnPercentageRequired(centerColumn, roomData.centerColumn,yLevel,0.15f)) {
            //return false;
            //}
            if (!(mineOption1 == Blocks.LANTERN || mineOption2 == Blocks.LANTERN)) {
                return false;
            }
        }
        if (roomData.name.contains("raid")) {
            if (raidOption == ModBlocks.RAID_CONTROLLER) {
                return true;
            }
        }
        if (roomData.name.contains("boss")) {
            if (raidOption == ModBlocks.RUNE_PILLAR) {
                return true; // variant 1 has pillar in same pos as raid controller
            }
            if (runeOption == ModBlocks.RUNE_PILLAR) {
                return true;
            }
        }

        for (Map<Integer, Block> column1 : columnList) {
            boolean flag = false;
            for (Map<Integer, Block> column2 : roomData.columnList) {

                if (compareColumn(column1, column2, yLevel)) {
                    //columnList.remove(column2);
                    flag = true;
                    break;
                }
            }
            if (!flag) {
                return false;
            }
        }
        return true;
    }

    public Tuple<RoomType, String> findRoom() {
        for (RoomData omegaRoom : omegaRooms) {
            if (this.compareRoom(omegaRoom)) {
                return new Tuple<>(RoomType.ROOMTYPE_OMEGA, omegaRoom.name);
            }
        }
        for (RoomData challengeRoom : challengeRooms) {
            if (this.compareRoom(challengeRoom)) {
                return new Tuple<>(RoomType.ROOMTYPE_CHALLENGE, challengeRoom.name);
            }
        }
        for (RoomData resourceRoom : resourceRooms) {
            if (this.compareRoom(resourceRoom)) {
                return new Tuple<>(RoomType.ROOMTYPE_RESOURCE, resourceRoom.name);
            }
        }
        // TODO: need to add support for vendor rooms

        return new Tuple<>(RoomType.ROOMTYPE_BASIC, "");
    }
}