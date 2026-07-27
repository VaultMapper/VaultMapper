package com.nodiumhosting.vaultmapper.commands;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.nodiumhosting.vaultmapper.map.VaultCell;
import com.nodiumhosting.vaultmapper.map.VaultMap;
import com.nodiumhosting.vaultmapper.map.VaultMapOverlayRenderer;
import com.nodiumhosting.vaultmapper.map.snapshots.MapCache;
import com.nodiumhosting.vaultmapper.map.snapshots.MapSnapshot;
import com.nodiumhosting.vaultmapper.proto.CellType;
import com.nodiumhosting.vaultmapper.config.BrazierTargetConfigManager;
import com.nodiumhosting.vaultmapper.config.RoomSignatureConfigManager;
import com.nodiumhosting.vaultmapper.config.RoomSpecialDetectionConfigManager;
import com.nodiumhosting.vaultmapper.config.RoomSpecialScanToggleConfigManager;
import com.nodiumhosting.vaultmapper.util.Util;
import com.nodiumhosting.vaultmapper.util.VaultDimensionUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.server.command.EnumArgument;

import java.util.HashMap;
import java.util.Map;


public class VaultMapperCommand {
    private static boolean ensureInVaultNamespace(Player player) {
        return VaultDimensionUtil.isInVaultNamespace(player);
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("vaultmapper")
                .executes(VaultMapperCommand::execute)
                .then(Commands.literal("enable")
                        .executes(VaultMapperCommand::execute)
                )
                .then(Commands.literal("disable")
                        .executes(VaultMapperCommand::execute)
                )
                .then(Commands.literal("reset")
                        .executes(VaultMapperCommand::execute)
                )
                .then(Commands.literal("enabledebug")
                        .executes(VaultMapperCommand::execute)
                )
                .then(Commands.literal("disabledebug")
                        .executes(VaultMapperCommand::execute)
                )
                .then(Commands.literal("openByVaultId")
                        .then(Commands.argument("vaultId", StringArgumentType.string())
                                .executes(VaultMapperCommand::execute)
                        )
                )
                .then(Commands.literal("dumpColumn")
                        .then(Commands.argument("column", EnumArgument.enumArgument(Column.class))
                                .executes(VaultMapperCommand::execute)
                        )
                )
                .then(Commands.literal("dumpMapCellData")
                        .executes(VaultMapperCommand::execute)
                )
                .then(Commands.literal("dumpCurrentCellData")
                    .executes(VaultMapperCommand::execute))
                .then(Commands.literal("copyRoomProbe")
                    .executes(VaultMapperCommand::execute))
                .then(Commands.literal("reloadRoomSignatures")
                    .executes(VaultMapperCommand::execute))
                .then(Commands.literal("reloadBrazierTargets")
                    .executes(VaultMapperCommand::execute))
                .then(Commands.literal("reloadRoomSpecialDetection")
                    .executes(VaultMapperCommand::execute))
        );
    }

    private static int execute(CommandContext<CommandSourceStack> command) {
        if (command.getSource().getEntity() instanceof Player player) {
            String[] args = command.getInput().split(" ");
            if (args.length > 1) {
                if (args[1].equals("enable")) {
                    VaultMap.resetMap();
                    VaultMap.enabled = true;
                    VaultMapOverlayRenderer.enabled = true;
                    MapCache.readCache();
                    player.sendMessage(new TextComponent("Vault Mapper enabled"), player.getUUID());
                } else if (args[1].equals("disable")) {
                    VaultMapOverlayRenderer.enabled = false;
                    VaultMap.enabled = false;
                    player.sendMessage(new TextComponent("Vault Mapper disabled"), player.getUUID());
                } else if (args[1].equals("reset")) {
                    VaultMap.resetMap();
                    player.sendMessage(new TextComponent("Vault Mapper reset"), player.getUUID());
                } else if (args[1].equals("enabledebug")) {
                    VaultMap.debug = true;
                } else if (args[1].equals("disabledebug")) {
                    VaultMap.debug = false;
                } else if (args[1].equals("openByVaultId")) {
                    if (args.length > 2) {
                        RenderSystem.recordRenderCall(() -> MapSnapshot.openScreen(args[2]));
                    } else {
                        player.sendMessage(new TextComponent("Usage: /vaultmapper openByVaultId <vaultId>"), player.getUUID());
                    }
                } else if (args[1].equals("dumpColumn")) {
                    if (!ensureInVaultNamespace(player)) return 0;

                    int blockX = 23;
                    int blockZ = 23;

                    switch (args[2]) {
                        case "MIDDLE":
                            blockX = 23;
                            blockZ = 23;
                            break;
                        case "NORTHWEST":
                            blockX = 0;
                            blockZ = 0;
                            break;
                        case "NORTHEAST":
                            blockX = 46;
                            blockZ = 0;
                            break;
                        case "SOUTHWEST":
                            blockX = 0;
                            blockZ = 46;
                            break;
                        case "SOUTHEAST":
                            blockX = 46;
                            blockZ = 46;
                            break;
                    }

                    // 9-55
                    VaultCell currentCell = VaultMap.getCurrentCell();

                    Map<Integer, String> middleColumn = new HashMap<>();
                    for (int i = 9; i <= 55; i++) {
                        Block block = VaultMap.getCellBlock(currentCell.x, currentCell.z, blockX, i, blockZ);
                        if (block != null) {
                            middleColumn.put(i, block.getRegistryName().toString());
                        }
                    }

                    String json = Util.GSON.toJson(middleColumn);
                    Minecraft.getInstance().keyboardHandler.setClipboard(json);
                } else if (args[1].equals("dumpMapCellData")) {
                    if (!ensureInVaultNamespace(player)) return 0;

                    String json = Util.GSON.toJson(VaultMap.cells);
                    Minecraft.getInstance().keyboardHandler.setClipboard(json);
                    player.sendMessage(new TextComponent(json), player.getUUID());
                } else if (args[1].equals("dumpCurrentCellData")) {
                    if (!ensureInVaultNamespace(player)) return 0;

                    VaultCell currentCell = VaultMap.getCurrentCell();
                    String json = Util.GSON.toJson(currentCell);
                    Minecraft.getInstance().keyboardHandler.setClipboard(json);
                    player.sendMessage(new TextComponent(json), player.getUUID());
                } else if (args[1].equals("copyRoomProbe")) {
                    if (!ensureInVaultNamespace(player)) {
                        player.sendMessage(new TextComponent("You can only use this command in a Vault"), player.getUUID());
                        return 0;
                    }

                    HitResult hitResult = Minecraft.getInstance().hitResult;
                    if (!(hitResult instanceof BlockHitResult blockHitResult) || hitResult.getType() != HitResult.Type.BLOCK) {
                        player.sendMessage(new TextComponent("Look at a block first"), player.getUUID());
                        return 0;
                    }

                    BlockPos targetPos = blockHitResult.getBlockPos();
                    Block targetBlock = player.level.getBlockState(targetPos).getBlock();
                    String blockId = targetBlock.getRegistryName() != null ? targetBlock.getRegistryName().toString() : "unknown:block";

                    int roomX = (int) Math.floor(player.getX() / 47);
                    int roomZ = (int) Math.floor(player.getZ() / 47);

                    int roomOriginX = roomX * 47;
                    int roomOriginZ = roomZ * 47;

                    VaultCell roomCell = VaultMap.getCurrentCell();

                    Map<String, Object> probe = new HashMap<>();
                    probe.put("dimension", player.level.dimension().location().toString());
                    probe.put("vaultId", player.level.dimension().location().getPath());

                    Map<String, Object> room = new HashMap<>();
                    room.put("roomX", roomX);
                    room.put("roomZ", roomZ);
                    room.put("roomOriginX", roomOriginX);
                    room.put("roomOriginZ", roomOriginZ);
                    room.put("detectedCellType", roomCell != null && roomCell.cellType != null ? roomCell.cellType.name() : CellType.CELLTYPE_UNKNOWN.name());
                    room.put("detectedRoomType", roomCell != null && roomCell.roomType != null ? roomCell.roomType.name() : "ROOMTYPE_UNKNOWN");
                    room.put("detectedRoomName", roomCell != null ? roomCell.roomName : "");
                    room.put("explored", roomCell != null && roomCell.explored);
                    probe.put("room", room);

                    Map<String, Object> lookedAtBlock = new HashMap<>();
                    int relativeX = targetPos.getX() - roomOriginX;
                    int relativeY = targetPos.getY();
                    int relativeZ = targetPos.getZ() - roomOriginZ;
                    lookedAtBlock.put("blockId", blockId);
                    lookedAtBlock.put("x", relativeX);
                    lookedAtBlock.put("y", relativeY);
                    lookedAtBlock.put("z", relativeZ);
                    lookedAtBlock.put("worldX", targetPos.getX());
                    lookedAtBlock.put("worldY", targetPos.getY());
                    lookedAtBlock.put("worldZ", targetPos.getZ());
                    lookedAtBlock.put("face", blockHitResult.getDirection().name());
                    probe.put("lookedAtBlock", lookedAtBlock);

                    String json = Util.GSON.toJson(probe);
                    Minecraft.getInstance().keyboardHandler.setClipboard(json);
                    player.sendMessage(new TextComponent("Room probe copied to clipboard"), player.getUUID());
                } else if (args[1].equals("reloadRoomSignatures")) {
                    boolean reloaded = RoomSignatureConfigManager.reload();
                    if (reloaded) {
                        VaultMap.invalidateRoomSignatureChecks();
                        int entries = RoomSignatureConfigManager.getActiveConfig().rooms.size();
                        player.sendMessage(new TextComponent("Room signatures reloaded: " + entries + " entries"), player.getUUID());
                    } else {
                        player.sendMessage(new TextComponent("Failed to reload room signatures. Check logs."), player.getUUID());
                    }
                    player.sendMessage(new TextComponent("Config path: " + RoomSignatureConfigManager.getConfigPath()), player.getUUID());
                } else if (args[1].equals("reloadBrazierTargets")) {
                    boolean reloaded = BrazierTargetConfigManager.reload();
                    if (reloaded) {
                        VaultMap.invalidateBrazierTargetMatches();
                        int entries = BrazierTargetConfigManager.getActiveConfig().desiredModifierSets.size();
                        player.sendMessage(new TextComponent("Brazier targets reloaded: " + entries + " set(s)"), player.getUUID());
                    } else {
                        player.sendMessage(new TextComponent("Failed to reload brazier targets. Check logs."), player.getUUID());
                    }
                    player.sendMessage(new TextComponent("Config path: " + BrazierTargetConfigManager.getConfigPath()), player.getUUID());
                } else if (args[1].equals("reloadRoomSpecialDetection")) {
                    boolean reloaded = RoomSpecialDetectionConfigManager.reload();
                    boolean togglesReloaded = RoomSpecialScanToggleConfigManager.reload();
                    if (reloaded && togglesReloaded) {
                        VaultMap.invalidateRoomSpecialDetections();
                        int entries = RoomSpecialDetectionConfigManager.getActiveConfig().features.size();
                        player.sendMessage(new TextComponent("Room special detection config reloaded: " + entries + " feature(s)"), player.getUUID());
                    } else {
                        player.sendMessage(new TextComponent("Failed to reload room special detection config. Check logs."), player.getUUID());
                    }
                    player.sendMessage(new TextComponent("Config path: " + RoomSpecialDetectionConfigManager.getConfigPath()), player.getUUID());
                    player.sendMessage(new TextComponent("Toggle path: " + RoomSpecialScanToggleConfigManager.getConfigPath()), player.getUUID());
                }
                else {
                    player.sendMessage(new TextComponent("Usage: /vaultmapper <enable|disable|reset|enabledebug|disabledebug|openByVaultId|dumpColumn|copyRoomProbe|reloadRoomSignatures|reloadBrazierTargets|reloadRoomSpecialDetection>"), player.getUUID());
                }
            } else {
                player.sendMessage(new TextComponent("Usage: /vaultmapper <enable|disable|reset|enabledebug|disabledebug|openByVaultId|dumpColumn|copyRoomProbe|reloadRoomSignatures|reloadBrazierTargets|reloadRoomSpecialDetection>"), player.getUUID());
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    enum Column {
        MIDDLE,
        NORTHWEST,
        NORTHEAST,
        SOUTHWEST,
        SOUTHEAST
    }
}
