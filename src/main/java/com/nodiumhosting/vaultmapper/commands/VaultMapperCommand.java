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
import com.nodiumhosting.vaultmapper.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.server.command.EnumArgument;

import java.util.HashMap;
import java.util.Map;


public class VaultMapperCommand {
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
                    if (!player.getLevel().dimension().location().getNamespace().equals("the_vault")) return 0;

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
                    if (!player.getLevel().dimension().location().getNamespace().equals("the_vault")) return 0;

                    String json = Util.GSON.toJson(VaultMap.cells);
                    Minecraft.getInstance().keyboardHandler.setClipboard(json);
                    player.sendMessage(new TextComponent(json), player.getUUID());
                } else if (args[1].equals("dumpCurrentCellData")) {
                    if (!player.getLevel().dimension().location().getNamespace().equals("the_vault")) return 0;

                    VaultCell currentCell = VaultMap.getCurrentCell();
                    String json = Util.GSON.toJson(currentCell);
                    Minecraft.getInstance().keyboardHandler.setClipboard(json);
                    player.sendMessage(new TextComponent(json), player.getUUID());
                }
                else {
                    player.sendMessage(new TextComponent("Usage: /vaultmapper <enable|disable|reset|enabledebug|disabledebug|openByVaultId|dumpColumn>"), player.getUUID());
                }
            } else {
                player.sendMessage(new TextComponent("Usage: /vaultmapper <enable|disable|reset|enabledebug|disabledebug|openByVaultId|dumpColumn>"), player.getUUID());
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