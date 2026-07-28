package com.nodiumhosting.vaultmapper.gui.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nodiumhosting.vaultmapper.VaultMapper;
import com.nodiumhosting.vaultmapper.config.ClientConfig;
import com.nodiumhosting.vaultmapper.config.RoomSpecialDetectionConfig;
import com.nodiumhosting.vaultmapper.config.RoomSpecialDetectionConfigManager;
import com.nodiumhosting.vaultmapper.config.RoomSpecialFeatureDefinition;
import com.nodiumhosting.vaultmapper.config.RoomSpecialScanToggleConfigManager;
import com.nodiumhosting.vaultmapper.gui.component.*;
import com.nodiumhosting.vaultmapper.map.VaultMapOverlayRenderer;
import com.nodiumhosting.vaultmapper.util.Clamp;
import com.nodiumhosting.vaultmapper.util.ColorUtil;
import com.nodiumhosting.vaultmapper.util.Util;
import it.unimi.dsi.fastutil.Function;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextComponent;

import java.util.ArrayList;
import java.util.List;

public class VaultMapperConfigScreen extends Screen {

    private static final class SpecialToggleEntry {
        private final RoomSpecialFeatureDefinition featureDefinition;
        private final Button button;

        private SpecialToggleEntry(RoomSpecialFeatureDefinition featureDefinition, Button button) {
            this.featureDefinition = featureDefinition;
            this.button = button;
        }
    }

    public VaultMapperConfigScreen() {
        super(new TextComponent("Vault Mapper Config"));
    }

    private static int parseColor(String hexColor) {
        return ColorUtil.parseHexColor(hexColor);
    }

    private static String getSpecialToggleText(RoomSpecialFeatureDefinition featureDefinition) {
        String label = featureDefinition != null && featureDefinition.displayName != null && !featureDefinition.displayName.isEmpty()
                ? featureDefinition.displayName
                : featureDefinition != null && featureDefinition.id != null ? featureDefinition.id : "Special";
        return label + ": " + (RoomSpecialScanToggleConfigManager.isEnabled(featureDefinition.id) ? "On" : "Off");
    }

    private static void refreshSpecialToggleText(SpecialToggleEntry entry) {
        if (entry != null && entry.button != null && entry.featureDefinition != null) {
            entry.button.setMessage(new TextComponent(getSpecialToggleText(entry.featureDefinition)));
        }
    }

    private int getScaledY(float y) {
        float height = Minecraft.getInstance().getWindow().getGuiScaledHeight(); //Minecraft.getInstance().getWindow().getHeight() / 2;
        float piece = height / 22;
        float scaledY = piece * y;
        return (int) scaledY;
    }

    private final List<SpecialToggleEntry> specialToggleEntries = new ArrayList<>();
    private Slider mapScale;
    private Slider arrowScale;
    private Slider PCCutoff;
    private Slider iconCrop;
    private Slider identifiedSpecialIconScale;
    private Slider identificationExtraRadius;
    private Slider mapXAnchor;
    private Slider mapYAnchor;
    private EditBoxReset mapXOffset;
    private EditBoxReset mapYOffset;
    private EditBoxReset pointerColor;
    private EditBoxReset roomColor;
    private EditBoxReset startRoomColor;
    private EditBoxReset markedRoomColor;
    private EditBoxReset inscriptionRoomColor;
    private EditBoxReset omegaRoomColor;
    private EditBoxReset challengeRoomColor;
    private EditBoxReset oreRoomColor;
    private EditBoxReset resourceRoomColor;
    private EditBoxReset syncServer;
    private EditBoxReset syncColor;
    private EditBoxReset identifiedUndiscoveredRoomColor;
    private Button playerCentric;
    private Button showRoomIcons;
    private Button showInscription;
    private Button enableSyncButton;
    private Button showViewerCodeButton;
    private Button loadedRoomScanButton;
    private Button mapEnabledButton;
    private Button showRoomTileButton;
    private Button showSpecialTextButton;
    private Button showFeatureMarkersButton;
    private Slider roomTileScale;
    private EditBoxReset roomTileXOffset;
    private EditBoxReset roomTileYOffset;
    private Slider specialTextScale;
    private EditBoxReset specialTextXOffset;
    private EditBoxReset specialTextYOffset;
    private ColorPicker colorPicker;

    protected void init() {
        super.init();

        int elHeight = getScaledY(1) / 2;
        int elWidth = 100;
        elWidthColor = elWidth - elHeight - 5;

        initToggles(elHeight, elWidth);
        initSliders(elHeight, elWidth);
        initColorFields(elHeight, elWidth);
        initSyncFields(elHeight, elWidth);
        initOverlaySettings(elHeight, elWidth);
        initSaveReset();
    }

    private int elWidthColor;

    private void initToggles(int elHeight, int elWidth) {
        MutableComponent enabledText = new TextComponent("✔").withStyle(ChatFormatting.BOLD, ChatFormatting.GREEN);
        MutableComponent disabledText = new TextComponent("❌").withStyle(ChatFormatting.BOLD, ChatFormatting.RED);

        mapEnabledButton = new Button(this.width / 2 - 100, getScaledY(1), 97, Math.min((getScaledY(1) / 3) * 2, 20), new TextComponent("Map: " + (ClientConfig.MAP_ENABLED.get() ? "On" : "Off")), button -> {
            ClientConfig.MAP_ENABLED.set(!ClientConfig.MAP_ENABLED.get());
            ClientConfig.SPEC.save();
            button.setMessage(new TextComponent("Map: " + (ClientConfig.MAP_ENABLED.get() ? "On" : "Off")));
        });
        this.addRenderableWidget(mapEnabledButton);

        loadedRoomScanButton = new Button(this.width / 2 + 3, getScaledY(1), 97, Math.min((getScaledY(1) / 3) * 2, 20), new TextComponent("Scan: " + (ClientConfig.SCAN_LOADED_ROOMS.get() ? "On" : "Off")), button -> {
            ClientConfig.SCAN_LOADED_ROOMS.set(!ClientConfig.SCAN_LOADED_ROOMS.get());
            ClientConfig.SPEC.save();
            button.setMessage(new TextComponent("Scan: " + (ClientConfig.SCAN_LOADED_ROOMS.get() ? "On" : "Off")));
        },
            (pButton, pPoseStack, pMouseX, pMouseY) -> renderTooltip(pPoseStack, new TextComponent("Scan loaded rooms around the player"), pMouseX, pMouseY));
        this.addRenderableWidget(loadedRoomScanButton);

        RoomSpecialDetectionConfig detectionConfig = RoomSpecialDetectionConfigManager.getActiveConfig();
        if (detectionConfig != null && detectionConfig.getEnabledFeatures() != null) {
            int specialIndex = 0;
            for (RoomSpecialFeatureDefinition featureDefinition : detectionConfig.getEnabledFeatures()) {
                if (featureDefinition == null || featureDefinition.id == null || featureDefinition.id.isEmpty()) {
                    continue;
                }

                int buttonX = this.width - 220;
                int buttonY = getScaledY(1.5f + (specialIndex * 0.5f));
                Button specialButton = new Button(buttonX, buttonY, 210, Math.min((getScaledY(1) / 3) * 2, 20), new TextComponent(getSpecialToggleText(featureDefinition)), button -> {
                    RoomSpecialScanToggleConfigManager.toggle(featureDefinition.id);
                    refreshSpecialToggleText(new SpecialToggleEntry(featureDefinition, button));
                    VaultMapOverlayRenderer.prep();
                },
                    (pButton, pPoseStack, pMouseX, pMouseY) -> renderTooltip(pPoseStack, new TextComponent("Scan current room for " + featureDefinition.displayName), pMouseX, pMouseY));
                this.addRenderableWidget(specialButton);
                specialToggleEntries.add(new SpecialToggleEntry(featureDefinition, specialButton));
                specialIndex++;
            }
        }

        playerCentric = new Button(this.width / 2 + elWidth + 15, getScaledY(4), elHeight, Math.min(elHeight, 20),  ClientConfig.PLAYER_CENTRIC_RENDERING.get() ? enabledText : disabledText, button -> {
            ClientConfig.PLAYER_CENTRIC_RENDERING.set(!ClientConfig.PLAYER_CENTRIC_RENDERING.get());
            ClientConfig.SPEC.save();
            button.setMessage(ClientConfig.PLAYER_CENTRIC_RENDERING.get() ? enabledText : disabledText);
            },
            (pButton, pPoseStack, pMouseX, pMouseY) -> renderTooltip(pPoseStack, new TextComponent("Player Centric Rendering"), pMouseX, pMouseY));
        this.addRenderableWidget(playerCentric);

        showRoomIcons = new Button(this.width / 2 + elWidthColor + 5 + 10 + elHeight + 5, getScaledY(16.5f), elHeight, Math.min(elHeight, 20),  ClientConfig.SHOW_ROOM_ICONS.get() ? enabledText : disabledText, button -> {
            ClientConfig.SHOW_ROOM_ICONS.set(!ClientConfig.SHOW_ROOM_ICONS.get());
            ClientConfig.SPEC.save();
            button.setMessage(ClientConfig.SHOW_ROOM_ICONS.get() ? enabledText : disabledText);
            },
            (pButton, pPoseStack, pMouseX, pMouseY) -> renderTooltip(pPoseStack, new TextComponent("Show Room Icons"), pMouseX, pMouseY));
        this.addRenderableWidget(showRoomIcons);

        showInscription = new Button(this.width / 2 + elWidthColor + 5 + 10 + elHeight + 5, getScaledY(14), elHeight, Math.min(elHeight, 20),  ClientConfig.SHOW_INSCRIPTIONS.get() ? enabledText : disabledText, button -> {
            ClientConfig.SHOW_INSCRIPTIONS.set(!ClientConfig.SHOW_INSCRIPTIONS.get());
            ClientConfig.SPEC.save();
            button.setMessage(ClientConfig.SHOW_INSCRIPTIONS.get() ? enabledText : disabledText);
        },
            (pButton, pPoseStack, pMouseX, pMouseY) -> renderTooltip(pPoseStack, new TextComponent("Show Inscriptions"), pMouseX, pMouseY));
        this.addRenderableWidget(showInscription);
    }

    private void initSliders(int elHeight, int elWidth) {
        mapScale = new Slider(this.width / 2 + 10, getScaledY(2), "", ClientConfig.MAP_SCALE.get(), 30, 3, v -> (int) v / 10.0f + "x", elWidth, elHeight, 10);
        this.addRenderableWidget(mapScale);

        arrowScale = new Slider(this.width / 2 + 10, getScaledY(3), "", ClientConfig.ARROW_SCALE.get(), 30, 3, v -> (int) v / 10.0f + "x", elWidth, elHeight, 10);
        this.addRenderableWidget(arrowScale);

        PCCutoff = new Slider(this.width / 2 + 10, getScaledY(4), "", ClientConfig.PC_CUTOFF.get(), 30, 4, v -> (int) v + " cells", elWidth, elHeight, 20);
        this.addRenderableWidget(PCCutoff);

        iconCrop = new Slider(this.width / 2 + 10, getScaledY(5), "", ClientConfig.ICON_CROP.get(), 8, 0, v -> (int) v + " pixel" + ((int) v != 1 ? "s" : ""), elWidth, elHeight, 0);
        this.addRenderableWidget(iconCrop);

        identifiedSpecialIconScale = new Slider(this.width / 2 + 10, getScaledY(5.5f), "", ClientConfig.IDENTIFIED_SPECIAL_UNDISCOVERED_ICON_SCALE.get(), 100, 30, v -> (int) v + "%", elWidth, elHeight, 60);
        this.addRenderableWidget(identifiedSpecialIconScale);

        identificationExtraRadius = new Slider(this.width / 2 + 10, getScaledY(6.5f), "", ClientConfig.IDENTIFICATION_EXTRA_CELL_RADIUS.get(), 8, 0, v -> "+" + (int) v + " cells", elWidth, elHeight, 1);
        this.addRenderableWidget(identificationExtraRadius);

        mapXOffset = new EditBoxReset(this.font, this.width / 2 + 10, getScaledY(6), elWidth, elHeight, new TextComponent("MAP_X_OFFSET"), "0");
        mapXOffset.setValue(ClientConfig.MAP_X_OFFSET.get().toString());
        this.addRenderableWidget(mapXOffset);

        mapYOffset = new EditBoxReset(this.font, this.width / 2 + 10, getScaledY(7), elWidth, elHeight, new TextComponent("MAP_Y_OFFSET"), "0");
        mapYOffset.setValue(ClientConfig.MAP_Y_OFFSET.get().toString());
        this.addRenderableWidget(mapYOffset);

        mapXAnchor = new Slider(this.width / 2 + 10, getScaledY(8), "", ClientConfig.MAP_X_ANCHOR.get(), 4, 0, v -> {
            return switch ((int) v) { case 0 -> "Left"; case 1 -> "Left Center"; case 2 -> "Center"; case 3 -> "Right Center"; case 4 -> "Right"; default -> "Unknown"; };
        }, elWidth, elHeight, 4);
        this.addRenderableWidget(mapXAnchor);

        mapYAnchor = new Slider(this.width / 2 + 10, getScaledY(9), "", ClientConfig.MAP_Y_ANCHOR.get(), 4, 0, v -> {
            return switch ((int) v) { case 0 -> "Top"; case 1 -> "Top Center"; case 2 -> "Center"; case 3 -> "Bottom Center"; case 4 -> "Bottom"; default -> "Unknown"; };
        }, elWidth, elHeight, 4);
        this.addRenderableWidget(mapYAnchor);

        colorPicker = new ColorPicker(Clamp.clamp(this.width / 2 + 200, 0, this.width - 200), getScaledY(7), 200, 200, parseColor("#000000"), button -> {
        });
        colorPicker.visible = false;
        this.addRenderableWidget(colorPicker);

        Button showTunnels = new Button(this.width / 2 + elWidthColor + 5 + 10 + elHeight + 5, getScaledY(11), elHeight, Math.min(elHeight, 20),  ClientConfig.SHOW_TUNNELS.get() ? new TextComponent("✔").withStyle(ChatFormatting.BOLD, ChatFormatting.GREEN) : new TextComponent("❌").withStyle(ChatFormatting.BOLD, ChatFormatting.RED), button -> {
            ClientConfig.SHOW_TUNNELS.set(!ClientConfig.SHOW_TUNNELS.get());
            ClientConfig.SPEC.save();
            MutableComponent t = ClientConfig.SHOW_TUNNELS.get() ? new TextComponent("✔").withStyle(ChatFormatting.BOLD, ChatFormatting.GREEN) : new TextComponent("❌").withStyle(ChatFormatting.BOLD, ChatFormatting.RED);
            button.setMessage(t);
        },
            (pButton, pPoseStack, pMouseX, pMouseY) -> renderTooltip(pPoseStack, new TextComponent("Show Tunnels"), pMouseX, pMouseY));
        this.addRenderableWidget(showTunnels);
    }

    private void initColorFields(int elHeight, int elWidth) {
        pointerColor = new EditBoxReset(this.font, this.width / 2 + 10, getScaledY(10), elWidthColor, elHeight, new TextComponent("POINTER_COLOR"), "#00FF00");
        pointerColor.setValue(ClientConfig.POINTER_COLOR.get());
        this.addRenderableWidget(pointerColor);
        ColorButton pointerColorPicker = new ColorButton(this.width / 2 + elWidthColor + 5 + 10, getScaledY(10), elHeight, elHeight, parseColor(ClientConfig.POINTER_COLOR.get()), button -> {}, pointerColor, colorPicker);
        this.addRenderableWidget(pointerColorPicker);
        pointerColor.setResponder((value) -> pointerColorPicker.setColor(parseColor(value)));

        roomColor = new EditBoxReset(this.font, this.width / 2 + 10, getScaledY(11), elWidthColor, elHeight, new TextComponent("ROOM_COLOR"), "#0000FF");
        roomColor.setValue(ClientConfig.ROOM_COLOR.get());
        this.addRenderableWidget(roomColor);
        ColorButton roomColorPicker = new ColorButton(this.width / 2 + elWidthColor + 5 + 10, getScaledY(11), elHeight, elHeight, parseColor(ClientConfig.ROOM_COLOR.get()), button -> {}, roomColor, colorPicker);
        this.addRenderableWidget(roomColorPicker);
        roomColor.setResponder((value) -> roomColorPicker.setColor(parseColor(value)));

        startRoomColor = new EditBoxReset(this.font, this.width / 2 + 10, getScaledY(12), elWidthColor, elHeight, new TextComponent("START_ROOM_COLOR"), "#FF0000");
        startRoomColor.setValue(ClientConfig.START_ROOM_COLOR.get());
        this.addRenderableWidget(startRoomColor);
        ColorButton startRoomColorPicker = new ColorButton(this.width / 2 + elWidthColor + 5 + 10, getScaledY(12), elHeight, elHeight, parseColor(ClientConfig.START_ROOM_COLOR.get()), button -> {}, startRoomColor, colorPicker);
        this.addRenderableWidget(startRoomColorPicker);
        startRoomColor.setResponder((value) -> startRoomColorPicker.setColor(parseColor(value)));

        markedRoomColor = new EditBoxReset(this.font, this.width / 2 + 10, getScaledY(13), elWidthColor, elHeight, new TextComponent("MARKED_ROOM_COLOR"), "#FF00FF");
        markedRoomColor.setValue(ClientConfig.MARKED_ROOM_COLOR.get());
        this.addRenderableWidget(markedRoomColor);
        ColorButton markedRoomColorPicker = new ColorButton(this.width / 2 + elWidthColor + 5 + 10, getScaledY(13), elHeight, elHeight, parseColor(ClientConfig.MARKED_ROOM_COLOR.get()), button -> {}, markedRoomColor, colorPicker);
        this.addRenderableWidget(markedRoomColorPicker);
        markedRoomColor.setResponder((value) -> markedRoomColorPicker.setColor(parseColor(value)));

        inscriptionRoomColor = new EditBoxReset(this.font, this.width / 2 + 10, getScaledY(14), elWidthColor, elHeight, new TextComponent("INSCRIPTION_ROOM_COLOR"), "#FFFF00");
        inscriptionRoomColor.setValue(ClientConfig.INSCRIPTION_ROOM_COLOR.get());
        this.addRenderableWidget(inscriptionRoomColor);
        ColorButton inscriptionRoomColorPicker = new ColorButton(this.width / 2 + elWidthColor + 5 + 10, getScaledY(14), elHeight, elHeight, parseColor(ClientConfig.INSCRIPTION_ROOM_COLOR.get()), button -> {}, inscriptionRoomColor, colorPicker);
        this.addRenderableWidget(inscriptionRoomColorPicker);
        inscriptionRoomColor.setResponder((value) -> inscriptionRoomColorPicker.setColor(parseColor(value)));

        omegaRoomColor = new EditBoxReset(this.font, this.width / 2 + 10, getScaledY(15), elWidthColor, elHeight, new TextComponent("OMEGA_ROOM_COLOR"), "#55FF55");
        omegaRoomColor.setValue(ClientConfig.OMEGA_ROOM_COLOR.get());
        this.addRenderableWidget(omegaRoomColor);
        ColorButton omegaRoomColorPicker = new ColorButton(this.width / 2 + elWidthColor + 5 + 10, getScaledY(15), elHeight, elHeight, parseColor(ClientConfig.OMEGA_ROOM_COLOR.get()), button -> {}, omegaRoomColor, colorPicker);
        this.addRenderableWidget(omegaRoomColorPicker);
        omegaRoomColor.setResponder((value) -> omegaRoomColorPicker.setColor(parseColor(value)));

        challengeRoomColor = new EditBoxReset(this.font, this.width / 2 + 10, getScaledY(16), elWidthColor, elHeight, new TextComponent("CHALLENGE_ROOM_COLOR"), "#F09E00");
        challengeRoomColor.setValue(ClientConfig.CHALLENGE_ROOM_COLOR.get());
        this.addRenderableWidget(challengeRoomColor);
        ColorButton challengeRoomColorPicker = new ColorButton(this.width / 2 + elWidthColor + 5 + 10, getScaledY(16), elHeight, elHeight, parseColor(ClientConfig.CHALLENGE_ROOM_COLOR.get()), button -> {}, challengeRoomColor, colorPicker);
        this.addRenderableWidget(challengeRoomColorPicker);
        challengeRoomColor.setResponder((value) -> challengeRoomColorPicker.setColor(parseColor(value)));

        oreRoomColor = new EditBoxReset(this.font, this.width / 2 + 10, getScaledY(17), elWidthColor, elHeight, new TextComponent("ORE_ROOM_COLOR"), "#00FFFF");
        oreRoomColor.setValue(ClientConfig.ORE_ROOM_COLOR.get());
        this.addRenderableWidget(oreRoomColor);
        ColorButton oreRoomColorPicker = new ColorButton(this.width / 2 + elWidthColor + 5 + 10, getScaledY(17), elHeight, elHeight, parseColor(ClientConfig.ORE_ROOM_COLOR.get()), button -> {}, oreRoomColor, colorPicker);
        this.addRenderableWidget(oreRoomColorPicker);
        oreRoomColor.setResponder((value) -> oreRoomColorPicker.setColor(parseColor(value)));

        resourceRoomColor = new EditBoxReset(this.font, this.width / 2 + 10, getScaledY(18), elWidthColor, elHeight, new TextComponent("RESOURCE_ROOM_COLOR"), "#FFFFFF");
        resourceRoomColor.setValue(ClientConfig.RESOURCE_ROOM_COLOR.get());
        this.addRenderableWidget(resourceRoomColor);
        ColorButton resourceRoomColorPicker = new ColorButton(this.width / 2 + elWidthColor + 5 + 10, getScaledY(18), elHeight, elHeight, parseColor(ClientConfig.RESOURCE_ROOM_COLOR.get()), button -> {}, resourceRoomColor, colorPicker);
        this.addRenderableWidget(resourceRoomColorPicker);
        resourceRoomColor.setResponder((value) -> resourceRoomColorPicker.setColor(parseColor(value)));

        identifiedUndiscoveredRoomColor = new EditBoxReset(this.font, this.width / 2 + 10, getScaledY(21), elWidthColor, elHeight, new TextComponent("IDENTIFIED_UNDISCOVERED_ROOM_COLOR"), "#7A8896");
        identifiedUndiscoveredRoomColor.setValue(ClientConfig.IDENTIFIED_UNDISCOVERED_ROOM_COLOR.get());
        this.addRenderableWidget(identifiedUndiscoveredRoomColor);
        ColorButton identifiedUndiscoveredRoomColorPicker = new ColorButton(this.width / 2 + elWidthColor + 5 + 10, getScaledY(21), elHeight, elHeight, parseColor(ClientConfig.IDENTIFIED_UNDISCOVERED_ROOM_COLOR.get()), button -> {}, identifiedUndiscoveredRoomColor, colorPicker);
        this.addRenderableWidget(identifiedUndiscoveredRoomColorPicker);
        identifiedUndiscoveredRoomColor.setResponder((value) -> identifiedUndiscoveredRoomColorPicker.setColor(parseColor(value)));
    }

    private void initSyncFields(int elHeight, int elWidth) {
        syncServer = new EditBoxReset(this.font, this.width / 2 - 70, getScaledY(19), elWidthColor + 80, elHeight, new TextComponent("SYNC_SERVER"), "wss://vmsync.ndmh.xyz");
        syncServer.setValue(ClientConfig.VMSYNC_SERVER.get());
        this.addRenderableWidget(syncServer);

        MutableComponent enabledText = new TextComponent("✔").withStyle(ChatFormatting.BOLD, ChatFormatting.GREEN);
        MutableComponent disabledText = new TextComponent("❌").withStyle(ChatFormatting.BOLD, ChatFormatting.RED);

        enableSyncButton = new Button(this.width / 2 + elWidthColor + 5 + 10, getScaledY(19), elHeight, elHeight, ClientConfig.SYNC_ENABLED.get() ? enabledText : disabledText, button -> {
            ClientConfig.SYNC_ENABLED.set(!ClientConfig.SYNC_ENABLED.get());
            ClientConfig.SPEC.save();
            button.setMessage(ClientConfig.SYNC_ENABLED.get() ? enabledText : disabledText);
            },
            (pButton, pPoseStack, pMouseX, pMouseY) -> renderTooltip(pPoseStack, new TextComponent("Sync"), pMouseX, pMouseY));
        this.addRenderableWidget(enableSyncButton);

        showViewerCodeButton = new Button(this.width / 2 + elWidthColor + 5 + 10 + elHeight + 5, getScaledY(19), elHeight, elHeight, ClientConfig.SHOW_VIEWER_CODE.get() ? enabledText : disabledText, button -> {
            ClientConfig.SHOW_VIEWER_CODE.set(!ClientConfig.SHOW_VIEWER_CODE.get());
            ClientConfig.SPEC.save();
            button.setMessage(ClientConfig.SHOW_VIEWER_CODE.get() ? enabledText : disabledText);
        },
            (pButton, pPoseStack, pMouseX, pMouseY) -> renderTooltip(pPoseStack, new TextComponent("Show Viewer Code"), pMouseX, pMouseY));
        this.addRenderableWidget(showViewerCodeButton);

        syncColor = new EditBoxReset(this.font, this.width / 2 + 10, getScaledY(20), elWidthColor, elHeight, new TextComponent("SYNC_COLOR"), Util.RandomColor());
        syncColor.setValue(ClientConfig.SYNC_COLOR.get());
        this.addRenderableWidget(syncColor);
        ColorButton syncColorPicker = new ColorButton(this.width / 2 + elWidthColor + 5 + 10, getScaledY(20), elHeight, elHeight, parseColor(ClientConfig.SYNC_COLOR.get()), button -> {}, syncColor, colorPicker);
        this.addRenderableWidget(syncColorPicker);
        syncColor.setResponder((value) -> syncColorPicker.setColor(parseColor(value)));
    }

    private void initOverlaySettings(int elHeight, int elWidth) {
        int rightColX = this.width - 220;
        int btnW = 210;
        int toggleY = getScaledY(4);
        float rowStep = 0.5f;

        showRoomTileButton = new Button(rightColX, toggleY, btnW, Math.min((getScaledY(1) / 3) * 2, 20), new TextComponent("Room Tile: " + (ClientConfig.SHOW_ROOM_TILE.get() ? "On" : "Off")), button -> {
            ClientConfig.SHOW_ROOM_TILE.set(!ClientConfig.SHOW_ROOM_TILE.get());
            ClientConfig.SPEC.save();
            button.setMessage(new TextComponent("Room Tile: " + (ClientConfig.SHOW_ROOM_TILE.get() ? "On" : "Off")));
        });
        this.addRenderableWidget(showRoomTileButton);

        showSpecialTextButton = new Button(rightColX, getScaledY(4.5f), btnW, Math.min((getScaledY(1) / 3) * 2, 20), new TextComponent("Spec Text: " + (ClientConfig.SHOW_SPECIAL_TEXT.get() ? "On" : "Off")), button -> {
            ClientConfig.SHOW_SPECIAL_TEXT.set(!ClientConfig.SHOW_SPECIAL_TEXT.get());
            ClientConfig.SPEC.save();
            button.setMessage(new TextComponent("Spec Text: " + (ClientConfig.SHOW_SPECIAL_TEXT.get() ? "On" : "Off")));
        });
        this.addRenderableWidget(showSpecialTextButton);

        showFeatureMarkersButton = new Button(rightColX, getScaledY(5), btnW, Math.min((getScaledY(1) / 3) * 2, 20), new TextComponent("Feature Markers: " + (ClientConfig.SHOW_FEATURE_MARKERS.get() ? "On" : "Off")), button -> {
            ClientConfig.SHOW_FEATURE_MARKERS.set(!ClientConfig.SHOW_FEATURE_MARKERS.get());
            ClientConfig.SPEC.save();
            button.setMessage(new TextComponent("Feature Markers: " + (ClientConfig.SHOW_FEATURE_MARKERS.get() ? "On" : "Off")));
        });
        this.addRenderableWidget(showFeatureMarkersButton);

        roomTileScale = new Slider(rightColX, getScaledY(6), "Tile Size", ClientConfig.ROOM_TILE_SCALE.get(), 200, 20, v -> (int) v + "px", btnW, elHeight, 80);
        this.addRenderableWidget(roomTileScale);

        roomTileXOffset = new EditBoxReset(this.font, rightColX, getScaledY(7), btnW / 2 - 2, elHeight, new TextComponent("Tile X Off"), "0");
        roomTileXOffset.setValue(ClientConfig.ROOM_TILE_X_OFFSET.get().toString());
        this.addRenderableWidget(roomTileXOffset);

        roomTileYOffset = new EditBoxReset(this.font, rightColX + btnW / 2 + 2, getScaledY(7), btnW / 2 - 2, elHeight, new TextComponent("Tile Y Off"), "0");
        roomTileYOffset.setValue(ClientConfig.ROOM_TILE_Y_OFFSET.get().toString());
        this.addRenderableWidget(roomTileYOffset);

        specialTextScale = new Slider(rightColX, getScaledY(8), "Spec Scale", ClientConfig.SPECIAL_TEXT_SCALE.get(), 30, 3, v -> (int) v / 10.0f + "x", btnW, elHeight, 10);
        this.addRenderableWidget(specialTextScale);

        specialTextXOffset = new EditBoxReset(this.font, rightColX, getScaledY(9), btnW / 2 - 2, elHeight, new TextComponent("Spec X Off"), "0");
        specialTextXOffset.setValue(ClientConfig.SPECIAL_TEXT_X_OFFSET.get().toString());
        this.addRenderableWidget(specialTextXOffset);

        specialTextYOffset = new EditBoxReset(this.font, rightColX + btnW / 2 + 2, getScaledY(9), btnW / 2 - 2, elHeight, new TextComponent("Spec Y Off"), "0");
        specialTextYOffset.setValue(ClientConfig.SPECIAL_TEXT_Y_OFFSET.get().toString());
        this.addRenderableWidget(specialTextYOffset);
    }

    private void initSaveReset() {
        Button saveButton = new Button(this.width - 100 - 5, this.height - 20 - 5, 100,  20, new TextComponent("Save"), button -> {
            saveConfig();
        });
        this.addRenderableWidget(saveButton);

        Button resetButton = new Button(5, this.height - 20 - 5, 100,20, new TextComponent("Reset"), button -> {
            resetConfig();
        });
        this.addRenderableWidget(resetButton);
    }

    private void saveConfig() {
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
        ClientConfig.IDENTIFIED_SPECIAL_UNDISCOVERED_ICON_SCALE.set(identifiedSpecialIconScale.sliderValue);
        ClientConfig.IDENTIFICATION_EXTRA_CELL_RADIUS.set(identificationExtraRadius.sliderValue);
        ClientConfig.IDENTIFIED_UNDISCOVERED_ROOM_COLOR.set(identifiedUndiscoveredRoomColor.getValue());
        ClientConfig.ROOM_TILE_SCALE.set(roomTileScale.sliderValue);
        try {
            ClientConfig.ROOM_TILE_X_OFFSET.set(Integer.parseInt(roomTileXOffset.getValue()));
        } catch (NumberFormatException e) {
            roomTileXOffset.setValue("2");
            ClientConfig.ROOM_TILE_X_OFFSET.set(2);
        }
        try {
            ClientConfig.ROOM_TILE_Y_OFFSET.set(Integer.parseInt(roomTileYOffset.getValue()));
        } catch (NumberFormatException e) {
            roomTileYOffset.setValue("2");
            ClientConfig.ROOM_TILE_Y_OFFSET.set(2);
        }
        ClientConfig.SPECIAL_TEXT_SCALE.set(specialTextScale.sliderValue);
        try {
            ClientConfig.SPECIAL_TEXT_X_OFFSET.set(Integer.parseInt(specialTextXOffset.getValue()));
        } catch (NumberFormatException e) {
            specialTextXOffset.setValue("0");
            ClientConfig.SPECIAL_TEXT_X_OFFSET.set(0);
        }
        try {
            ClientConfig.SPECIAL_TEXT_Y_OFFSET.set(Integer.parseInt(specialTextYOffset.getValue()));
        } catch (NumberFormatException e) {
            specialTextYOffset.setValue("0");
            ClientConfig.SPECIAL_TEXT_Y_OFFSET.set(0);
        }
        ClientConfig.SPEC.save();

        VaultMapOverlayRenderer.prep();
    }

    private void resetConfig() {
        MutableComponent enabledText = new TextComponent("✔").withStyle(ChatFormatting.BOLD, ChatFormatting.GREEN);
        MutableComponent disabledText = new TextComponent("❌").withStyle(ChatFormatting.BOLD, ChatFormatting.RED);

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
        loadedRoomScanButton.setMessage(new TextComponent("Scan: Off"));
        RoomSpecialScanToggleConfigManager.setAllEnabled(false);
        for (SpecialToggleEntry specialToggleEntry : specialToggleEntries) {
            refreshSpecialToggleText(specialToggleEntry);
        }
        String randColor = Util.RandomColor();
        syncColor.setValue(randColor);

        PCCutoff.sliderValue = 20;
        playerCentric.setMessage(disabledText);
        iconCrop.sliderValue = 0;
        identifiedSpecialIconScale.sliderValue = 60;
        identificationExtraRadius.sliderValue = 1;
        identifiedUndiscoveredRoomColor.setValue("#7A8896");

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
        ClientConfig.SCAN_LOADED_ROOMS.set(false);

        ClientConfig.PC_CUTOFF.set(20);
        ClientConfig.PLAYER_CENTRIC_RENDERING.set(false);
        ClientConfig.PC_BORDER.set(true);
        ClientConfig.IDENTIFIED_SPECIAL_UNDISCOVERED_ICON_SCALE.set(60);
        ClientConfig.IDENTIFICATION_EXTRA_CELL_RADIUS.set(1);
        ClientConfig.IDENTIFIED_UNDISCOVERED_ROOM_COLOR.set("#7A8896");

        showRoomTileButton.setMessage(enabledText);
        showSpecialTextButton.setMessage(enabledText);
        showFeatureMarkersButton.setMessage(enabledText);
        roomTileScale.sliderValue = 80;
        roomTileXOffset.setValue("0");
        roomTileYOffset.setValue("0");
        specialTextScale.sliderValue = 10;
        specialTextXOffset.setValue("0");
        specialTextYOffset.setValue("0");

        ClientConfig.ROOM_TILE_SCALE.set(80);
        ClientConfig.ROOM_TILE_X_OFFSET.set(0);
        ClientConfig.ROOM_TILE_Y_OFFSET.set(0);
        ClientConfig.SPECIAL_TEXT_SCALE.set(10);
        ClientConfig.SPECIAL_TEXT_X_OFFSET.set(0);
        ClientConfig.SPECIAL_TEXT_Y_OFFSET.set(0);
        ClientConfig.SHOW_FEATURE_MARKERS.set(true);
        ClientConfig.SPEC.save();

        VaultMapOverlayRenderer.onWindowResize();
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
        this.font.draw(pose, "Undisc Special Icon Scale", this.width / 2 - 110, getScaledY(5.5f) + offsetY, 0xFFFFFFFF);
        this.font.draw(pose, "Identify Extra Radius", this.width / 2 - 110, getScaledY(6.5f) + offsetY, 0xFFFFFFFF);
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
        this.font.draw(pose, "Identified Undisc Color", this.width / 2 - 110, getScaledY(21) + offsetY, 0xFFFFFFFF);
        this.font.draw(pose, "Tile Size", this.width - 220, getScaledY(6) + offsetY, 0xFFFFFFFF);
        this.font.draw(pose, "Tile Offset X / Y", this.width - 220, getScaledY(7) + offsetY, 0xFFFFFFFF);
        this.font.draw(pose, "Spec Scale", this.width - 220, getScaledY(8) + offsetY, 0xFFFFFFFF);
        this.font.draw(pose, "Spec Offset X / Y", this.width - 220, getScaledY(9) + offsetY, 0xFFFFFFFF);

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
