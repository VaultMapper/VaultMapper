package com.nodiumhosting.vaultmapper.gui.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nodiumhosting.vaultmapper.VaultMapper;
import com.nodiumhosting.vaultmapper.config.ClientConfig;
import com.nodiumhosting.vaultmapper.gui.component.*;
import com.nodiumhosting.vaultmapper.map.VaultMapOverlayRenderer;
import com.nodiumhosting.vaultmapper.util.Clamp;
import com.nodiumhosting.vaultmapper.util.Util;
import it.unimi.dsi.fastutil.Function;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextComponent;

public class VaultMapperConfigScreen extends Screen {

    public VaultMapperConfigScreen() {
        super(new TextComponent("Vault Mapper Config"));
    }

    // copied block from overlay renderer, should move elsewhere
    private static int parseColor(String hexColor) {
        try {
            if (hexColor.startsWith("#")) {
                hexColor = hexColor.substring(1);
            }

            if (hexColor.length() == 6) {
                hexColor = "FF" + hexColor;  // Add full opacity if not specified
            }

            // Cast to int to use it as a 32-bit ARGB color
            return (int) Long.parseLong(hexColor, 16);
        } catch (NumberFormatException e) {
            return 0xFFFFFFFF; // Default color (white with full opacity)
        }
    }

    private int getScaledY(float y) {
        float height = Minecraft.getInstance().getWindow().getGuiScaledHeight(); //Minecraft.getInstance().getWindow().getHeight() / 2;
        float piece = height / 22;
        float scaledY = piece * y;
        return (int) scaledY;
    }

    protected void init() {
        super.init();

        int elHeight = getScaledY(1) / 2;
        int elWidth = 200;
        int width = 100;
        int elWidthColor = width - elHeight - 5;

        Button mapEnabledButton = new Button(this.width / 2 - 100, getScaledY(1), elWidth, Math.min((getScaledY(1) / 3) * 2, 20), new TextComponent("Map Enabled: " + ClientConfig.MAP_ENABLED.get()), button -> {
            ClientConfig.MAP_ENABLED.set(!ClientConfig.MAP_ENABLED.get());
            ClientConfig.SPEC.save();
            button.setMessage(new TextComponent("Map Enabled: " + ClientConfig.MAP_ENABLED.get()));
        });
        this.addRenderableWidget(mapEnabledButton);

        Function<Float, String> mapScaleGetter = (value) -> {
            int valueInt = (int) value;
            float floatValue = (float) valueInt / 10;
            String stringValue = floatValue + "x";
//            if (valueInt == 10) {
//                stringValue += " (Default)";
//            }
            return stringValue;
        };

        Function<Float, String> PCCutoffGetter = (value) -> {
            int valueInt = (int) value;
            return valueInt + " cells";
        };

        Function<Float, String> cropGetter = (value) -> {
            int valueInt = (int) value;
            return valueInt + " pixel" + (valueInt != 1 ? "s" : "");
        };

        Slider mapScale = new Slider(this.width / 2 + 10, getScaledY(2), "", ClientConfig.MAP_SCALE.get(), 30, 3, mapScaleGetter, width, elHeight, 10);
        this.addRenderableWidget(mapScale);

        Slider arrowScale = new Slider(this.width / 2 + 10, getScaledY(3), "", ClientConfig.ARROW_SCALE.get(), 30, 3, mapScaleGetter, width, elHeight, 10);
        this.addRenderableWidget(arrowScale);

        Slider PCCutoff = new Slider(this.width / 2 + 10, getScaledY(4), "", ClientConfig.PC_CUTOFF.get(), 30, 4, PCCutoffGetter, width, elHeight, 20);
        this.addRenderableWidget(PCCutoff);

        Slider iconCrop = new Slider(this.width / 2 + 10, getScaledY(5), "", ClientConfig.ICON_CROP.get(), 8, 0, cropGetter, width, elHeight, 0);
        this.addRenderableWidget(iconCrop);

        MutableComponent enabledText = new TextComponent("✔").withStyle(ChatFormatting.BOLD, ChatFormatting.GREEN);
        MutableComponent disabledText = new TextComponent("❌").withStyle(ChatFormatting.BOLD, ChatFormatting.RED);

        Button playerCentric = new Button(this.width / 2 + width + 15, getScaledY(4), elHeight, Math.min(elHeight, 20),  ClientConfig.PLAYER_CENTRIC_RENDERING.get() ? enabledText : disabledText, button -> {
            ClientConfig.PLAYER_CENTRIC_RENDERING.set(!ClientConfig.PLAYER_CENTRIC_RENDERING.get());
            ClientConfig.SPEC.save();
            button.setMessage(ClientConfig.PLAYER_CENTRIC_RENDERING.get() ? enabledText : disabledText);
            },
            (pButton, pPoseStack, pMouseX, pMouseY) -> renderTooltip(pPoseStack, new TextComponent("Player Centric Rendering"), pMouseX, pMouseY));
        this.addRenderableWidget(playerCentric);

        Button enablePCBorderButton = new Button(this.width / 2 + width + 15 + 2 + elHeight, getScaledY(4), elHeight, Math.min(elHeight, 20), ClientConfig.PC_BORDER.get() ? enabledText : disabledText, button -> {
            ClientConfig.PC_BORDER.set(!ClientConfig.PC_BORDER.get());
            ClientConfig.SPEC.save();
            button.setMessage(ClientConfig.PC_BORDER.get() ? enabledText : disabledText);
            },
            (pButton, pPoseStack, pMouseX, pMouseY) -> {
                renderTooltip(pPoseStack, new TextComponent("Player Centric Border"), pMouseX, pMouseY);
            });
        this.addRenderableWidget(enablePCBorderButton);

        EditBoxReset mapXOffset = new EditBoxReset(this.font, this.width / 2 + 10, getScaledY(6), width, elHeight, new TextComponent("MAP_X_OFFSET"), "0");
        mapXOffset.setValue(ClientConfig.MAP_X_OFFSET.get().toString());
        this.addRenderableWidget(mapXOffset);

        EditBoxReset mapYOffset = new EditBoxReset(this.font, this.width / 2 + 10, getScaledY(7), width, elHeight, new TextComponent("MAP_Y_OFFSET"), "0");
        mapYOffset.setValue(ClientConfig.MAP_Y_OFFSET.get().toString());
        this.addRenderableWidget(mapYOffset);

        Function<Float, String> anchorGetterX = (value) -> {
            switch ((int) value) {
                case 0:
                    return "Left";
                case 1:
                    return "Left Center";
                case 2:
                    return "Center";
                case 3:
                    return "Right Center";
                case 4:
                    return "Right";
                default:
                    return "Unknown";
            }
        };
        Slider mapXAnchor = new Slider(this.width / 2 + 10, getScaledY(8), "", ClientConfig.MAP_X_ANCHOR.get(), 4, 0, anchorGetterX, width, elHeight, 4);
        this.addRenderableWidget(mapXAnchor);

        Function<Float, String> anchorGetterY = (value) -> {
            switch ((int) value) {
                case 0:
                    return "Top";
                case 1:
                    return "Top Center";
                case 2:
                    return "Center";
                case 3:
                    return "Bottom Center";
                case 4:
                    return "Bottom";
                default:
                    return "Unknown";
            }
        };

        Slider mapYAnchor = new Slider(this.width / 2 + 10, getScaledY(9), "", ClientConfig.MAP_Y_ANCHOR.get(), 4, 0, anchorGetterY, width, elHeight, 4);
        this.addRenderableWidget(mapYAnchor);

        ColorPicker colorPicker = new ColorPicker(Clamp.clamp(this.width / 2 + 200, 0, this.width - 200), getScaledY(7), 200, 200, parseColor("#000000"), button -> {
        });
        colorPicker.visible = false;
        this.addRenderableWidget(colorPicker);

        EditBoxReset pointerColor = new EditBoxReset(this.font, this.width / 2 + 10, getScaledY(10), elWidthColor, elHeight, new TextComponent("POINTER_COLOR"), "#00FF00");
        pointerColor.setValue(ClientConfig.POINTER_COLOR.get());
        this.addRenderableWidget(pointerColor);
        ColorButton pointerColorPicker = new ColorButton(this.width / 2 + elWidthColor + 5 + 10, getScaledY(10), elHeight, elHeight, parseColor(ClientConfig.POINTER_COLOR.get()), button -> {

        }, pointerColor, colorPicker);
        this.addRenderableWidget(pointerColorPicker);
        pointerColor.setResponder((value) -> pointerColorPicker.setColor(parseColor(value)));

        EditBoxReset roomColor = new EditBoxReset(this.font, this.width / 2 + 10, getScaledY(11), elWidthColor, elHeight, new TextComponent("ROOM_COLOR"), "#0000FF");
        roomColor.setValue(ClientConfig.ROOM_COLOR.get());
        this.addRenderableWidget(roomColor);
        ColorButton roomColorPicker = new ColorButton(this.width / 2 + elWidthColor + 5 + 10, getScaledY(11), elHeight, elHeight, parseColor(ClientConfig.ROOM_COLOR.get()), button -> {

        }, roomColor, colorPicker);
        this.addRenderableWidget(roomColorPicker);
        roomColor.setResponder((value) -> roomColorPicker.setColor(parseColor(value)));

        Button showTunnels = new Button(this.width / 2 + elWidthColor + 5 + 10 + elHeight + 5, getScaledY(11), elHeight, Math.min(elHeight, 20),  ClientConfig.SHOW_TUNNELS.get() ? enabledText : disabledText, button -> {
            ClientConfig.SHOW_TUNNELS.set(!ClientConfig.SHOW_TUNNELS.get());
            ClientConfig.SPEC.save();
            button.setMessage(ClientConfig.SHOW_TUNNELS.get() ? enabledText : disabledText);
        },
            (pButton, pPoseStack, pMouseX, pMouseY) -> renderTooltip(pPoseStack, new TextComponent("Show Tunnels"), pMouseX, pMouseY));
        this.addRenderableWidget(showTunnels);

        EditBoxReset startRoomColor = new EditBoxReset(this.font, this.width / 2 + 10, getScaledY(12), elWidthColor, elHeight, new TextComponent("START_ROOM_COLOR"), "#FF0000");
        startRoomColor.setValue(ClientConfig.START_ROOM_COLOR.get());
        this.addRenderableWidget(startRoomColor);
        ColorButton startRoomColorPicker = new ColorButton(this.width / 2 + elWidthColor + 5 + 10, getScaledY(12), elHeight, elHeight, parseColor(ClientConfig.START_ROOM_COLOR.get()), button -> {

        }, startRoomColor, colorPicker);
        this.addRenderableWidget(startRoomColorPicker);
        startRoomColor.setResponder((value) -> {
            startRoomColorPicker.setColor(parseColor(value));
        });

        EditBoxReset markedRoomColor = new EditBoxReset(this.font, this.width / 2 + 10, getScaledY(13), elWidthColor, elHeight, new TextComponent("MARKED_ROOM_COLOR"), "#FF00FF");
        markedRoomColor.setValue(ClientConfig.MARKED_ROOM_COLOR.get());
        this.addRenderableWidget(markedRoomColor);
        ColorButton markedRoomColorPicker = new ColorButton(this.width / 2 + elWidthColor + 5 + 10, getScaledY(13), elHeight, elHeight, parseColor(ClientConfig.MARKED_ROOM_COLOR.get()), button -> {

        }, markedRoomColor, colorPicker);
        this.addRenderableWidget(markedRoomColorPicker);
        markedRoomColor.setResponder((value) -> markedRoomColorPicker.setColor(parseColor(value)));

        EditBoxReset inscriptionRoomColor = new EditBoxReset(this.font, this.width / 2 + 10, getScaledY(14), elWidthColor, elHeight, new TextComponent("INSCRIPTION_ROOM_COLOR"), "#FFFF00");
        inscriptionRoomColor.setValue(ClientConfig.INSCRIPTION_ROOM_COLOR.get());
        this.addRenderableWidget(inscriptionRoomColor);
        ColorButton inscriptionRoomColorPicker = new ColorButton(this.width / 2 + elWidthColor + 5 + 10, getScaledY(14), elHeight, elHeight, parseColor(ClientConfig.INSCRIPTION_ROOM_COLOR.get()), button -> {

        }, inscriptionRoomColor, colorPicker);
        this.addRenderableWidget(inscriptionRoomColorPicker);
        inscriptionRoomColor.setResponder((value) -> inscriptionRoomColorPicker.setColor(parseColor(value)));
        Button showInscription = new Button(this.width / 2 + elWidthColor + 5 + 10 + elHeight + 5, getScaledY(14), elHeight, Math.min(elHeight, 20),  ClientConfig.SHOW_INSCRIPTIONS.get() ? enabledText : disabledText, button -> {
            ClientConfig.SHOW_INSCRIPTIONS.set(!ClientConfig.SHOW_INSCRIPTIONS.get());
            ClientConfig.SPEC.save();
            button.setMessage(ClientConfig.SHOW_INSCRIPTIONS.get() ? enabledText : disabledText);
        },
            (pButton, pPoseStack, pMouseX, pMouseY) -> renderTooltip(pPoseStack, new TextComponent("Show Inscriptions"), pMouseX, pMouseY));
        this.addRenderableWidget(showInscription);

        EditBoxReset omegaRoomColor = new EditBoxReset(this.font, this.width / 2 + 10, getScaledY(15), elWidthColor, elHeight, new TextComponent("OMEGA_ROOM_COLOR"), "#55FF55");
        omegaRoomColor.setValue(ClientConfig.OMEGA_ROOM_COLOR.get());
        this.addRenderableWidget(omegaRoomColor);
        ColorButton omegaRoomColorPicker = new ColorButton(this.width / 2 + elWidthColor + 5 + 10, getScaledY(15), elHeight, elHeight, parseColor(ClientConfig.OMEGA_ROOM_COLOR.get()), button -> {

        }, omegaRoomColor, colorPicker);
        this.addRenderableWidget(omegaRoomColorPicker);
        omegaRoomColor.setResponder((value) -> omegaRoomColorPicker.setColor(parseColor(value)));

        EditBoxReset challengeRoomColor = new EditBoxReset(this.font, this.width / 2 + 10, getScaledY(16), elWidthColor, elHeight, new TextComponent("CHALLENGE_ROOM_COLOR"), "#F09E00");
        challengeRoomColor.setValue(ClientConfig.CHALLENGE_ROOM_COLOR.get());
        this.addRenderableWidget(challengeRoomColor);
        ColorButton challengeRoomColorPicker = new ColorButton(this.width / 2 + elWidthColor + 5 + 10, getScaledY(16), elHeight, elHeight, parseColor(ClientConfig.CHALLENGE_ROOM_COLOR.get()), button -> {

        }, challengeRoomColor, colorPicker);
        this.addRenderableWidget(challengeRoomColorPicker);
        challengeRoomColor.setResponder((value) -> challengeRoomColorPicker.setColor(parseColor(value)));

        EditBoxReset oreRoomColor = new EditBoxReset(this.font, this.width / 2 + 10, getScaledY(17), elWidthColor, elHeight, new TextComponent("ORE_ROOM_COLOR"), "#00FFFF");
        oreRoomColor.setValue(ClientConfig.ORE_ROOM_COLOR.get());
        this.addRenderableWidget(oreRoomColor);
        ColorButton oreRoomColorPicker = new ColorButton(this.width / 2 + elWidthColor + 5 + 10, getScaledY(17), elHeight, elHeight, parseColor(ClientConfig.ORE_ROOM_COLOR.get()), button -> {

        }, oreRoomColor, colorPicker);
        this.addRenderableWidget(oreRoomColorPicker);
        oreRoomColor.setResponder((value) -> oreRoomColorPicker.setColor(parseColor(value)));

        EditBoxReset resourceRoomColor = new EditBoxReset(this.font, this.width / 2 + 10, getScaledY(18), elWidthColor, elHeight, new TextComponent("RESOURCE_ROOM_COLOR"), "#FFFFFF");
        resourceRoomColor.setValue(ClientConfig.RESOURCE_ROOM_COLOR.get());
        this.addRenderableWidget(resourceRoomColor);
        ColorButton resourceRoomColorPicker = new ColorButton(this.width / 2 + elWidthColor + 5 + 10, getScaledY(18), elHeight, elHeight, parseColor(ClientConfig.RESOURCE_ROOM_COLOR.get()), button -> {

        }, resourceRoomColor, colorPicker);
        this.addRenderableWidget(resourceRoomColorPicker);
        resourceRoomColor.setResponder((value) -> {
            resourceRoomColorPicker.setColor(parseColor(value));
        });

        Button showRoomIcons = new Button(this.width / 2 + elWidthColor + 5 + 10 + elHeight + 5, getScaledY(16.5f), elHeight, Math.min(elHeight, 20),  ClientConfig.SHOW_ROOM_ICONS.get() ? enabledText : disabledText, button -> {
            ClientConfig.SHOW_ROOM_ICONS.set(!ClientConfig.SHOW_ROOM_ICONS.get());
            ClientConfig.SPEC.save();
            button.setMessage(ClientConfig.SHOW_ROOM_ICONS.get() ? enabledText : disabledText);
            },
            (pButton, pPoseStack, pMouseX, pMouseY) -> renderTooltip(pPoseStack, new TextComponent("Show Room Icons"), pMouseX, pMouseY));
        this.addRenderableWidget(showRoomIcons);

        EditBoxReset syncServer = new EditBoxReset(this.font, this.width / 2 - 70, getScaledY(19), elWidthColor + 80, elHeight, new TextComponent("SYNC_SERVER"), "wss://vmsync.ndmh.xyz");
        syncServer.setValue(ClientConfig.VMSYNC_SERVER.get());
        this.addRenderableWidget(syncServer);

        Button enableSyncButton = new Button(this.width / 2 + elWidthColor + 5 + 10, getScaledY(19), elHeight, elHeight, ClientConfig.SYNC_ENABLED.get() ? enabledText : disabledText, button -> {
            ClientConfig.SYNC_ENABLED.set(!ClientConfig.SYNC_ENABLED.get());
            ClientConfig.SPEC.save();
            button.setMessage(ClientConfig.SYNC_ENABLED.get() ? enabledText : disabledText);
            },
            (pButton, pPoseStack, pMouseX, pMouseY) -> renderTooltip(pPoseStack, new TextComponent("Sync"), pMouseX, pMouseY));
        this.addRenderableWidget(enableSyncButton);

        Button showViewerCodeButton = new Button(this.width / 2 + elWidthColor + 5 + 10 + elHeight + 5, getScaledY(19), elHeight, elHeight, ClientConfig.SHOW_VIEWER_CODE.get() ? enabledText : disabledText, button -> {
            ClientConfig.SHOW_VIEWER_CODE.set(!ClientConfig.SHOW_VIEWER_CODE.get());
            ClientConfig.SPEC.save();
            button.setMessage(ClientConfig.SHOW_VIEWER_CODE.get() ? enabledText : disabledText);
        },
            (pButton, pPoseStack, pMouseX, pMouseY) -> renderTooltip(pPoseStack, new TextComponent("Show Viewer Code"), pMouseX, pMouseY));
        this.addRenderableWidget(showViewerCodeButton);

        EditBoxReset syncColor = new EditBoxReset(this.font, this.width / 2 + 10, getScaledY(20), elWidthColor, elHeight, new TextComponent("SYNC_COLOR"), Util.RandomColor());
        syncColor.setValue(ClientConfig.SYNC_COLOR.get());
        this.addRenderableWidget(syncColor);
        ColorButton syncColorPicker = new ColorButton(this.width / 2 + elWidthColor + 5 + 10, getScaledY(20), elHeight, elHeight, parseColor(ClientConfig.SYNC_COLOR.get()), button -> {

        }, syncColor, colorPicker);
        this.addRenderableWidget(syncColorPicker);
        syncColor.setResponder((value) -> syncColorPicker.setColor(parseColor(value)));

        Button saveButton = new Button(this.width - 100 - 5, this.height - 20 - 5, 100,  20, new TextComponent("Save"), button -> {
            try {
                ClientConfig.MAP_X_OFFSET.set(Integer.parseInt(mapXOffset.getValue()));
            } catch (NumberFormatException e) {
                mapXOffset.setValue("0");
                ClientConfig.MAP_X_OFFSET.set(0);
            }
            try {
                ClientConfig.MAP_Y_OFFSET.set(Integer.parseInt(mapYOffset.getValue()));
            } catch (NumberFormatException e) {
                mapYOffset.setValue("0");
                ClientConfig.MAP_Y_OFFSET.set(0);
            }
            ClientConfig.MAP_SCALE.set(mapScale.sliderValue);
            ClientConfig.ARROW_SCALE.set(arrowScale.sliderValue);
            ClientConfig.MAP_X_ANCHOR.set(mapXAnchor.sliderValue);
            ClientConfig.MAP_Y_ANCHOR.set(mapYAnchor.sliderValue);
            ClientConfig.POINTER_COLOR.set(pointerColor.getValue());
            ClientConfig.ROOM_COLOR.set(roomColor.getValue());
            ClientConfig.START_ROOM_COLOR.set(startRoomColor.getValue());
            ClientConfig.MARKED_ROOM_COLOR.set(markedRoomColor.getValue());
            ClientConfig.INSCRIPTION_ROOM_COLOR.set(inscriptionRoomColor.getValue());
            ClientConfig.OMEGA_ROOM_COLOR.set(omegaRoomColor.getValue());
            ClientConfig.CHALLENGE_ROOM_COLOR.set(challengeRoomColor.getValue());
            ClientConfig.ORE_ROOM_COLOR.set(oreRoomColor.getValue());
            ClientConfig.RESOURCE_ROOM_COLOR.set(resourceRoomColor.getValue());
            ClientConfig.VMSYNC_SERVER.set(syncServer.getValue());
            ClientConfig.SYNC_COLOR.set(syncColor.getValue());

            ClientConfig.PC_CUTOFF.set(PCCutoff.sliderValue);
            ClientConfig.ICON_CROP.set(iconCrop.sliderValue);
            ClientConfig.SPEC.save();

            VaultMapOverlayRenderer.prep();
        });
        this.addRenderableWidget(saveButton);

        Button resetButton = new Button(5, this.height - 20 - 5, 100,20, new TextComponent("Reset"), button -> {
            mapScale.sliderValue = 10;
            arrowScale.sliderValue = 10;
            mapXOffset.setValue("0");
            mapYOffset.setValue("0");
            mapXAnchor.sliderValue = 4;
            mapYAnchor.sliderValue = 4;
            pointerColor.setValue("#00FF00");
            roomColor.setValue("#0000FF");
            startRoomColor.setValue("#FF0000");
            markedRoomColor.setValue("#FF00FF");
            inscriptionRoomColor.setValue("#FFFF00");
            showRoomIcons.setMessage(enabledText);

            omegaRoomColor.setValue("#55FF55");
            challengeRoomColor.setValue("#F09E00");
            oreRoomColor.setValue("#00FFFF");
            resourceRoomColor.setValue("#FFFFFF");
            showInscription.setMessage(enabledText);
            syncServer.setValue("wss://vmsync.ndmh.xyz");
            enableSyncButton.setMessage(enabledText);
            showViewerCodeButton.setMessage(disabledText);
            String randColor = Util.RandomColor();

            syncColor.setValue(randColor);

            PCCutoff.sliderValue = 20;
            playerCentric.setMessage(disabledText);
            enablePCBorderButton.setMessage(enabledText);
            iconCrop.sliderValue = 0;

            ClientConfig.MAP_SCALE.set(10);
            ClientConfig.ARROW_SCALE.set(10);
            ClientConfig.MAP_X_OFFSET.set(0);
            ClientConfig.MAP_Y_OFFSET.set(0);
            ClientConfig.MAP_X_ANCHOR.set(4);
            ClientConfig.MAP_Y_ANCHOR.set(4);
            ClientConfig.POINTER_COLOR.set("#00FF00");
            ClientConfig.ROOM_COLOR.set("#0000FF");
            ClientConfig.START_ROOM_COLOR.set("#FF0000");
            ClientConfig.MARKED_ROOM_COLOR.set("#FF00FF");
            ClientConfig.INSCRIPTION_ROOM_COLOR.set("#FFFF00");
            ClientConfig.SHOW_INSCRIPTIONS.set(true);
            ClientConfig.OMEGA_ROOM_COLOR.set("#55FF55");
            ClientConfig.CHALLENGE_ROOM_COLOR.set("#F09E00");
            ClientConfig.ORE_ROOM_COLOR.set("#00FFFF");
            ClientConfig.RESOURCE_ROOM_COLOR.set("#FFFFFF");
            ClientConfig.SHOW_ROOM_ICONS.set(true);
            ClientConfig.VMSYNC_SERVER.set("wss://vmsync.ndmh.xyz");
            ClientConfig.SYNC_ENABLED.set(true);
            ClientConfig.SYNC_COLOR.set(randColor);
            ClientConfig.SHOW_VIEWER_CODE.set(false);

            ClientConfig.PC_CUTOFF.set(20);
            ClientConfig.PLAYER_CENTRIC_RENDERING.set(false);
            ClientConfig.PC_BORDER.set(true);

            ClientConfig.SPEC.save();

            VaultMapOverlayRenderer.onWindowResize();
        });
        this.addRenderableWidget(resetButton);

        //DEBUG
//        this.addRenderableOnly(new MapComponent(0, 0, 384, 384, VaultMap.getCells()));
    }

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(pose);

        //mod version in the upper left
        this.font.draw(pose, "Vault Mapper v" + VaultMapper.getVersion(), 8, 8, 0xFFFFFFFF);

        this.font.draw(pose, "Vault Mapper Config", this.width / 2 - this.font.width("Vault Mapper Config") / 2, 10, 0xFFFFFFFF);

        // labels
        int offsetY = getScaledY(1) / 8;
        this.font.draw(pose, "Map Scale", this.width / 2 - 110, getScaledY(2) + offsetY, 0xFFFFFFFF);
        this.font.draw(pose, "Arrow Scale", this.width / 2 - 110, getScaledY(3) + offsetY, 0xFFFFFFFF);
        this.font.draw(pose, "Player Centric Cutoff", this.width / 2 - 110, getScaledY(4) + offsetY, 0xFFFFFFFF);
        this.font.draw(pose, "Icon Crop", this.width / 2 - 110, getScaledY(5) + offsetY, 0xFFFFFFFF);
        this.font.draw(pose, "Map X Offset", this.width / 2 - 110, getScaledY(6) + offsetY, 0xFFFFFFFF);
        this.font.draw(pose, "Map Y Offset", this.width / 2 - 110, getScaledY(7) + offsetY, 0xFFFFFFFF);
        this.font.draw(pose, "Map X Anchor", this.width / 2 - 110, getScaledY(8) + offsetY, 0xFFFFFFFF);
        this.font.draw(pose, "Map Y Anchor", this.width / 2 - 110, getScaledY(9) + offsetY, 0xFFFFFFFF);
        this.font.draw(pose, "Pointer Color", this.width / 2 - 110, getScaledY(10) + offsetY, 0xFFFFFFFF);
        this.font.draw(pose, "Room Color", this.width / 2 - 110, getScaledY(11) + offsetY, 0xFFFFFFFF);
        this.font.draw(pose, "Start Room Color", this.width / 2 - 110, getScaledY(12) + offsetY, 0xFFFFFFFF);
        this.font.draw(pose, "Marked Room Color", this.width / 2 - 110, getScaledY(13) + offsetY, 0xFFFFFFFF);
        this.font.draw(pose, "Inscription Room Color", this.width / 2 - 110, getScaledY(14) + offsetY, 0xFFFFFFFF);
        this.font.draw(pose, "Omega Room Color", this.width / 2 - 110, getScaledY(15) + offsetY, 0xFFFFFFFF);
        this.font.draw(pose, "Challenge Room Color", this.width / 2 - 110, getScaledY(16) + offsetY, 0xFFFFFFFF);
        this.font.draw(pose, "Ore Room Color", this.width / 2 - 110, getScaledY(17) + offsetY, 0xFFFFFFFF);
        this.font.draw(pose, "Resource Room Color", this.width / 2 - 110, getScaledY(18) + offsetY, 0xFFFFFFFF);
        this.font.draw(pose, "VMSync", this.width / 2 - 110, getScaledY(19) + offsetY, 0xFFFFFFFF);
        this.font.draw(pose, "Sync Color", this.width / 2 - 110, getScaledY(20) + offsetY, 0xFFFFFFFF);

        super.render(pose, mouseX, mouseY, partialTick);

        // Render things after widgets (tooltips)
    }

    @Override
    public void onClose() {
        // Stop any handlers here

        // Call last in case it interferes with the override
        super.onClose();

        ColorButton.clearListeners();
    }

    @Override
    public void removed() {
        // Reset initial states here

        // Call last in case it interferes with the override
        super.removed();

        ColorButton.clearListeners();

    }
}
