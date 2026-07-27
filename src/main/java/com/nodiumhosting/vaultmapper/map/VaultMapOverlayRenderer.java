package com.nodiumhosting.vaultmapper.map;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.nodiumhosting.vaultmapper.VaultMapper;
import com.nodiumhosting.vaultmapper.config.ClientConfig;
import com.nodiumhosting.vaultmapper.config.RoomSpecialScanToggleConfigManager;
import com.nodiumhosting.vaultmapper.map.special.BearingIndicatorRenderer;
import com.nodiumhosting.vaultmapper.map.special.RoomSpecialTextRenderer;
import com.nodiumhosting.vaultmapper.map.special.VaultRoomTileRenderer;
import net.minecraft.core.BlockPos;
import com.nodiumhosting.vaultmapper.proto.CellType;
import com.nodiumhosting.vaultmapper.proto.RoomType;
import com.nodiumhosting.vaultmapper.util.ColorUtil;
import com.nodiumhosting.vaultmapper.util.MapRoomIconUtil;
import iskallia.vault.core.vault.ClientVaults;
import iskallia.vault.core.vault.Vault;
import iskallia.vault.core.vault.VaultUtils;
import iskallia.vault.util.McClientHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec2;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;

import static java.lang.Math.abs;

@Mod.EventBusSubscriber({Dist.CLIENT})
public class VaultMapOverlayRenderer {
    public static boolean enabled = false;
    public static boolean syncErrorState = false;
    static boolean playerCentricRender = ClientConfig.PLAYER_CENTRIC_RENDERING.get();
    static int cutoff = ClientConfig.PC_CUTOFF.get();
    static float mapScaleMultiplier;
    static float mapRoomWidth;
    static boolean prepped = false;
    static float centerX;
    static float centerZ;
    static float mapAnchorX = 0;
    static float mapAnchorZ = 0;
    static int playerX;
    static int playerZ;

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void eventHandler(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
        if (!enabled) return;
        if (!ClientConfig.MAP_ENABLED.get()) return;
        if (VaultMap.isQuickMapHiddenInVault()) return;
        Vault vault = ClientVaults.getActive().orElse(null);
        if (vault != null) {
            if(VaultUtils.isHeraldVault(vault)) {
                return;
            }
        }
        if (!prepped) prep();

        int offsetX = ClientConfig.MAP_X_OFFSET.get();
        int offsetZ = ClientConfig.MAP_Y_OFFSET.get();

        var mcPlayer = Minecraft.getInstance().player;
        if (mcPlayer != null) {
            playerX = (int) Math.floor(mcPlayer.getX() / 47.0);
            playerZ = (int) Math.floor(mcPlayer.getZ() / 47.0);
        } else if (VaultMap.currentRoom != null) {
            playerX = VaultMap.currentRoom.x;
            playerZ = VaultMap.currentRoom.z;
        } else {
            playerX = 0;
            playerZ = 0;
        }
        if (syncErrorState) {
            float offset = playerCentricRender ? (cutoff + 1) * mapRoomWidth : (VaultMap.northSize + 1) * mapRoomWidth;
            TextComponent syncError = new TextComponent("Sync Error");
            GuiComponent.drawCenteredString(event.getMatrixStack(), Minecraft.getInstance().font, syncError, (int) centerX + offsetX, (int) mapAnchorZ + offsetZ - (int) offset - 9, 0xFFFFFF);
        }

        if (VaultMap.viewerCode != null && ClientConfig.SHOW_VIEWER_CODE.get()) {
            float offset = playerCentricRender ? (cutoff + 1) * mapRoomWidth : (VaultMap.southSize + 1) * mapRoomWidth;
            TextComponent syncError = new TextComponent("Viewer Code: " + VaultMap.viewerCode);
            GuiComponent.drawCenteredString(event.getMatrixStack(), Minecraft.getInstance().font, syncError, (int) centerX + offsetX, (int) mapAnchorZ + offsetZ + (int) offset, 0xFFFFFF);
        }

        VaultRoomTileRenderer.render(event.getMatrixStack());

        BlockPos cakePos = VaultMap.getCurrentRoomCakePosition();
        if (cakePos != null) {
            BearingIndicatorRenderer.render(event.getMatrixStack(), "Cake", cakePos);
        }

        if (ClientConfig.SHOW_SPECIAL_TEXT.get()) {
            ArrayList<String> specialLines = new ArrayList<>();
        String brazierOverlayLine = VaultMap.getCurrentRoomBrazierOverlayText();
        if (brazierOverlayLine != null && !brazierOverlayLine.isEmpty()) {
            specialLines.add("BRAZIER|" + brazierOverlayLine);
        }
        String cakeOverlayLine = VaultMap.getCurrentRoomCakeOverlayText();
        if (cakeOverlayLine != null && !cakeOverlayLine.isEmpty()) {
            specialLines.add("CAKE|" + cakeOverlayLine);
        }
        List<String> pylonLines = VaultMap.getCurrentRoomPylonOverlayLines();
        if (pylonLines != null && !pylonLines.isEmpty()) {
            for (String line : pylonLines) {
                specialLines.add("PYLON|" + line);
            }
        }
        List<String> godChallengeLines = VaultMap.getCurrentRoomGodAltarOverlayLines();
        if (godChallengeLines != null && !godChallengeLines.isEmpty()) {
            for (String line : godChallengeLines) {
                specialLines.add("GOD|" + line);
            }
        }

        if (!specialLines.isEmpty()) {
            int guiHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
            int guiWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();

            float specialTextScale = Math.max(0.38f, Math.min(0.62f, mapScaleMultiplier * 0.5f));
            int scaledLineHeight = Math.max(1, Math.round((Minecraft.getInstance().font.lineHeight + 1) * specialTextScale));
            int groupGap = Math.max(1, Math.round(2.0f * specialTextScale));
            int totalGap = 0;
            for (int i = 1; i < specialLines.size(); i++) {
                String prevType = specialLines.get(i - 1).split("\\|", 2)[0];
                String currentType = specialLines.get(i).split("\\|", 2)[0];
                if (!prevType.equals(currentType)) {
                    totalGap += groupGap;
                }
            }
            int textBlockHeight = specialLines.size() * scaledLineHeight + totalGap;

            // Position at center-bottom with space for hotbar
            float startY = guiHeight - textBlockHeight - 64;
            float lineY = startY;
            String previousType = null;
            for (String specialLine : specialLines) {
                String currentType = specialLine.split("\\|", 2)[0];
                if (previousType != null && !previousType.equals(currentType)) {
                    lineY += groupGap;
                }

                RoomSpecialTextRenderer.drawSpecialInfoLine(event.getMatrixStack(), guiWidth / 2, lineY, specialLine, VaultMap.currentRoomBrazierMatchesTarget(), specialTextScale);
                lineY += scaledLineHeight;
                previousType = currentType;
            }
        }
        }

        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        RenderSystem.enableBlend();
        RenderSystem.disableTexture();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        // Tunnel map
        if (ClientConfig.SHOW_TUNNELS.get()) {
            for (VaultCell vaultCell : VaultMap.cells) {
                if ((vaultCell.cellType == CellType.CELLTYPE_TUNNEL_X || vaultCell.cellType == CellType.CELLTYPE_TUNNEL_Z) && shouldRenderCell(vaultCell)) {
                    renderStyledCell(bufferBuilder, vaultCell);
                }
            }
        }

        // cell map
        for (VaultCell vaultCell : VaultMap.cells) {
            if (vaultCell.cellType == CellType.CELLTYPE_ROOM && shouldRenderCell(vaultCell)) {
                renderStyledCell(bufferBuilder, vaultCell);
            }
        }

        bufferBuilder.end();
        BufferUploader.end(bufferBuilder); // render the map

        RenderSystem.enableTexture();
        RenderSystem.disableBlend();

        // render icons
        if (ClientConfig.SHOW_ROOM_ICONS.get()) {
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.disableBlend();
            for (VaultCell vaultCell : VaultMap.cells) {
                if (vaultCell.cellType != CellType.CELLTYPE_ROOM || !shouldRenderCell(vaultCell)) {
                    continue;
                }
                if (!vaultCell.explored && !vaultCell.inscripted && !isIdentifiedSpecialUndiscoveredRoom(vaultCell)) {
                    continue;
                }

                if (vaultCell.roomName == null || vaultCell.roomName.isEmpty()) {
                    vaultCell.roomName = vaultCell.roomType.name();
                }

                try {
                    ResourceLocation icon = MapRoomIconUtil.getIconForRoom(vaultCell.roomName);
                    if (icon == null) {
                        VaultMapper.LOGGER.error("Icon {} not found for room: {}", icon, vaultCell.roomName);
                        continue;
                    }
                    RenderSystem.setShaderTexture(0, icon);
                    bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);

                    renderTextureCell(bufferBuilder, vaultCell);
                } catch (Exception e) {
                    VaultMapper.LOGGER.error("Failed to render icon for room: " + vaultCell.roomName);
                }

                bufferBuilder.end();
                BufferUploader.end(bufferBuilder);
            }

            RenderSystem.enableBlend();
            RenderSystem.disableTexture();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);

            bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

            // cell map
            for (VaultCell cell : VaultMap.cells) {
                if (cell.cellType == CellType.CELLTYPE_ROOM && (cell.inscripted || cell.marked) && shouldRenderCell(cell)) {
                    renderCellBorder(bufferBuilder, cell, parseColor(VaultMap.getCellColor(cell)));
                }
            }

            bufferBuilder.end();
            BufferUploader.end(bufferBuilder); // render the map
        }

        RenderSystem.enableTexture();
        RenderSystem.disableBlend();

        PoseStack poseStack = event.getMatrixStack();
        for (VaultCell cell : VaultMap.cells) {
            if (cell.cellType != CellType.CELLTYPE_ROOM || !shouldRenderCell(cell)) {
                continue;
            }

            boolean discoveredRoom = cell.explored;
            boolean identifiedUndiscoveredRoom = !cell.explored && isIdentifiedSpecialUndiscoveredRoom(cell);
            if (!discoveredRoom && !identifiedUndiscoveredRoom) {
                continue;
            }

            String centerLetter = VaultMap.getRoomCenterIndicatorLetter(cell);

            if (centerLetter == null || centerLetter.isEmpty()) {
                continue;
            }

            renderBossTypeLetter(poseStack, cell, centerLetter);
        }

        RenderSystem.enableBlend();
        RenderSystem.disableTexture();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        bufferBuilder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        // player thingies
        for (VaultMap.MapPlayer player : VaultMap.players.values()) {
            if(vault != null) {
                if(VaultUtils.isPvPVault(vault)) {
                    break;
                }

                renderPlayerArrow(bufferBuilder, player);
            }
        }
        bufferBuilder.end();
        BufferUploader.end(bufferBuilder);

        // player thingy TODO: Might need to adjust this thing as the previous loop handles it
        var currentPlayer = Minecraft.getInstance().player;
        if (currentPlayer != null) {
            float currentPlayerRoomX = (float) (currentPlayer.getX() / 47.0 - 0.5);
            float currentPlayerRoomZ = (float) (currentPlayer.getZ() / 47.0 - 0.5);

            bufferBuilder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            renderCurrentPlayerArrow(bufferBuilder, currentPlayerRoomX, currentPlayerRoomZ, currentPlayer.getYHeadRot(), parseColor(ClientConfig.POINTER_COLOR.get()));
            bufferBuilder.end();
            BufferUploader.end(bufferBuilder);
        }

        RenderSystem.enableBlend();
        RenderSystem.disableTexture();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        if (RoomSpecialScanToggleConfigManager.isEnabled("god_altar")) {
            for (VaultCell cell : VaultMap.cells) {
                if (cell.cellType == CellType.CELLTYPE_ROOM && shouldRenderCell(cell)) {
                    renderGodAltarIndicatorDots(bufferBuilder, cell);
                }
            }
        }
        if (RoomSpecialScanToggleConfigManager.isEnabled("pylon")) {
            for (VaultCell cell : VaultMap.cells) {
                if (cell.cellType == CellType.CELLTYPE_ROOM && shouldRenderCell(cell)) {
                    renderPylonIndicatorDots(bufferBuilder, cell);
                }
            }
        }
        if (RoomSpecialScanToggleConfigManager.isEnabled("cake")) {
            for (VaultCell cell : VaultMap.cells) {
                if (cell.cellType == CellType.CELLTYPE_ROOM && shouldRenderCell(cell)) {
                    renderCakeIndicatorDots(bufferBuilder, cell);
                }
            }
        }
        bufferBuilder.end();
        BufferUploader.end(bufferBuilder);

        RenderSystem.enableTexture();
        RenderSystem.disableBlend();

        if (Minecraft.getInstance().options.keyPlayerList.isDown()) {
            for (Map.Entry<String, VaultMap.MapPlayer> entry : VaultMap.players.entrySet()) {
                if(vault != null) {
                    if(VaultUtils.isPvPVault(vault)) {
                        return;
                    }

                    renderPlayerName(event.getMatrixStack(), entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private static void renderPlayerName(PoseStack posestack, String uuid, VaultMap.MapPlayer data) {
        int offsetX = ClientConfig.MAP_X_OFFSET.get();
        int offsetZ = ClientConfig.MAP_Y_OFFSET.get();
        float arrowX;
        float arrowZ;
        if (playerCentricRender) {
            if (abs(data.x - playerX) > cutoff || abs(data.y - playerZ) > cutoff) return;
            Vec2 rotated = rotateVector(data.x - playerX, data.y - playerZ);
            arrowX = centerX + rotated.x * mapRoomWidth + offsetX;
            arrowZ = centerZ + rotated.y * mapRoomWidth + offsetZ;
        } else {
            Vec2 rotated = rotateVector(data.x, data.y);
            arrowX = centerX + rotated.x * mapRoomWidth + offsetX;
            arrowZ = centerZ + rotated.y * mapRoomWidth + offsetZ;
        }

        UUID id = UUID.fromString(uuid);
        GameProfile profile = McClientHelper.getOnlineProfile(id).orElse(null);
        if (profile == null) {
            return;
        }
        String name = profile.getName();
        if (name == null || name.isEmpty()){
            return;
        }

        posestack.pushPose();
        float scale = ClientConfig.MAP_SCALE.get() * 0.1f * (float)(1/Minecraft.getInstance().getWindow().getGuiScale());
        posestack.translate((1 - scale) * arrowX, (1 - scale) * arrowZ, 0);
        posestack.scale(scale, scale, scale);

        GuiComponent.drawCenteredString(posestack, Minecraft.getInstance().font, name, (int) (arrowX + offsetX), (int) (arrowZ + offsetZ + 10), parseColor(data.color));
        posestack.popPose();
    }

    private static void renderPlayerArrow(BufferBuilder bufferBuilder, VaultMap.MapPlayer data) {
        int offsetX = ClientConfig.MAP_X_OFFSET.get();
        int offsetZ = ClientConfig.MAP_Y_OFFSET.get();
        float arrowX;
        float arrowZ;
        if (playerCentricRender) {
            if (abs(data.x - playerX) > cutoff || abs(data.y - playerZ) > cutoff) return;
            Vec2 rotated = rotateVector(data.x - playerX, data.y - playerZ);
            arrowX = centerX + rotated.x * mapRoomWidth + offsetX; //breaks with certain high values, god knows why
            arrowZ = centerZ + rotated.y * mapRoomWidth + offsetZ; //breaks with certain high values, god knows why
        } else {
            Vec2 rotated = rotateVector(data.x, data.y);
            arrowX = centerX + rotated.x * mapRoomWidth + offsetX;
            arrowZ = centerZ + rotated.y * mapRoomWidth + offsetZ;
        }

        float[] triag = getRotatedTriangle(data.yaw + getMapRotationDegrees());
        int color = parseColor(data.color);
        bufferBuilder.vertex(triag[0] + arrowX, triag[1] + arrowZ, 0).color(color).endVertex();
        bufferBuilder.vertex(triag[2] + arrowX, triag[3] + arrowZ, 0).color(color).endVertex();
        bufferBuilder.vertex(triag[4] + arrowX, triag[5] + arrowZ, 0).color(color).endVertex();
    }

    private static void renderCurrentPlayerArrow(BufferBuilder bufferBuilder, float exactRoomX, float exactRoomZ, float yaw, int color) {
        int offsetX = ClientConfig.MAP_X_OFFSET.get();
        int offsetZ = ClientConfig.MAP_Y_OFFSET.get();

        float arrowX;
        float arrowZ;
        if (playerCentricRender) {
            Vec2 rotated = rotateVector(exactRoomX - playerX, exactRoomZ - playerZ);
            arrowX = centerX + rotated.x * mapRoomWidth + offsetX;
            arrowZ = centerZ + rotated.y * mapRoomWidth + offsetZ;
        } else {
            Vec2 rotated = rotateVector(exactRoomX, exactRoomZ);
            arrowX = centerX + rotated.x * mapRoomWidth + offsetX;
            arrowZ = centerZ + rotated.y * mapRoomWidth + offsetZ;
        }

        float[] triag = getRotatedTriangle(yaw + getMapRotationDegrees());
        bufferBuilder.vertex(triag[0] + arrowX, triag[1] + arrowZ, 0).color(color).endVertex();
        bufferBuilder.vertex(triag[2] + arrowX, triag[3] + arrowZ, 0).color(color).endVertex();
        bufferBuilder.vertex(triag[4] + arrowX, triag[5] + arrowZ, 0).color(color).endVertex();
    }

    private static float[] getRotatedTriangle(float yaw) { // returns three points that make a rotated triangle when added with mapx,z
        double arrowScale = ClientConfig.ARROW_SCALE.get() * 0.03f * 0.7; // slightly smaller
        double s = mapRoomWidth * arrowScale;
        double x1 = 2.4 * s;
        double y1 = 0;
        double x2 = -1.2 * s;
        double y2 = -1.6 * s;
        double x3 = -1.2 * s;
        double y3 = 1.6 * s;

        // Shift triangle so the base center aligns with origin (player feet)
        double baseCenterX = -1.2 * s;
        x1 -= baseCenterX;
        x2 -= baseCenterX;
        x3 -= baseCenterX;

        float radangle = (float) Math.toRadians(yaw + 90);

        double[] rotatedVert1 = rotatePoint(x1, y1, 0, 0, radangle);
        double[] rotatedVert2 = rotatePoint(x2, y2, 0, 0, radangle);
        double[] rotatedVert3 = rotatePoint(x3, y3, 0, 0, radangle);

        return new float[] {
            (float) rotatedVert1[0], (float) rotatedVert1[1],
            (float) rotatedVert2[0], (float) rotatedVert2[1],
            (float) rotatedVert3[0], (float) rotatedVert3[1]
        };
    }

    private static double[] rotatePoint(double x, double y, double cx, double cy, double angle) {
        double cosTheta = Math.cos(angle);
        double sinTheta = Math.sin(angle);

        // Translate point to origin
        double translatedX = x - cx;
        double translatedY = y - cy;

        // Rotate point
        double rotatedX = translatedX * cosTheta - translatedY * sinTheta;
        double rotatedY = translatedX * sinTheta + translatedY * cosTheta;

        // Translate point back
        double finalX = rotatedX + cx;
        double finalY = rotatedY + cy;

        return new double[]{finalX, finalY};
    }

    private static boolean shouldRenderCell(VaultCell cell) {
        if (cell.inscripted && !cell.explored && !ClientConfig.SHOW_INSCRIPTIONS.get())
            return false;
        return !(playerCentricRender && (abs(cell.x - playerX) > cutoff || abs(cell.z - playerZ) > cutoff));
    }

    private static boolean isLoadedUndiscoveredRoom(VaultCell cell) {
        return cell.cellType == CellType.CELLTYPE_ROOM && !cell.explored && !cell.inscripted;
    }

    private static boolean isIdentifiedUndiscoveredNormalRoom(VaultCell cell) {
        if (cell == null || cell.cellType != CellType.CELLTYPE_ROOM || cell.explored || cell.inscripted) {
            return false;
        }
        if (isIdentifiedSpecialUndiscoveredRoom(cell)) {
            return false;
        }
        return cell.roomName != null && !cell.roomName.isEmpty();
    }

    private static boolean isIdentifiedSpecialUndiscoveredRoom(VaultCell cell) {
        if (cell == null || cell.explored || cell.cellType != CellType.CELLTYPE_ROOM) {
            return false;
        }

        if (cell.roomType == RoomType.ROOMTYPE_CHALLENGE || cell.roomType == RoomType.ROOMTYPE_OMEGA) {
            return true;
        }

        return cell.roomName != null && cell.roomName.toLowerCase().contains("boss");
    }

    private static void renderStyledCell(BufferBuilder bufferBuilder, VaultCell cell) {
        int color = parseColor(VaultMap.getCellColor(cell));

        if (!cell.explored && isIdentifiedSpecialUndiscoveredRoom(cell)) {
            renderCell(bufferBuilder, cell, withAlpha(darken(color, 0.16f), 0.90f), 0.60f);
            renderCell(bufferBuilder, cell, withAlpha(lighten(color, 0.06f), 0.98f), 0.52f);
            return;
        }

        if (isIdentifiedUndiscoveredNormalRoom(cell)) {
            int dimColor = parseColor(ClientConfig.IDENTIFIED_UNDISCOVERED_ROOM_COLOR.get());
            renderCell(bufferBuilder, cell, withAlpha(darken(dimColor, 0.20f), 0.62f), 0.76f);
            renderCell(bufferBuilder, cell, withAlpha(lighten(dimColor, 0.08f), 0.74f), 0.60f);
            return;
        }

        if (isLoadedUndiscoveredRoom(cell) && !isIdentifiedSpecialUndiscoveredRoom(cell)) {
            int dimColor = blendColor(color, 0xFF7A8896, 0.7f);
            renderCell(bufferBuilder, cell, withAlpha(darken(dimColor, 0.20f), 0.62f), 0.76f);
            renderCell(bufferBuilder, cell, withAlpha(lighten(dimColor, 0.08f), 0.74f), 0.60f);
            return;
        }

        renderCell(bufferBuilder, cell, withAlpha(darken(color, 0.16f), 0.90f), 1.0f);
        if (cell.cellType == CellType.CELLTYPE_ROOM) {
            renderCell(bufferBuilder, cell, withAlpha(lighten(color, 0.06f), 0.98f), 0.86f);
        }
    }

    private static int withAlpha(int color, float alpha) {
        int a = (int) (Math.max(0.0f, Math.min(1.0f, alpha)) * 255.0f);
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private static int blendColor(int from, int to, float ratio) {
        float t = Math.max(0.0f, Math.min(1.0f, ratio));
        int fr = (from >> 16) & 0xFF;
        int fg = (from >> 8) & 0xFF;
        int fb = from & 0xFF;

        int tr = (to >> 16) & 0xFF;
        int tg = (to >> 8) & 0xFF;
        int tb = to & 0xFF;

        int r = (int) (fr + (tr - fr) * t);
        int g = (int) (fg + (tg - fg) * t);
        int b = (int) (fb + (tb - fb) * t);

        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int darken(int color, float amount) {
        return blendColor(color, 0xFF000000, amount);
    }

    private static int lighten(int color, float amount) {
        return blendColor(color, 0xFFFFFFFF, amount);
    }

    public static void renderCell(BufferBuilder bufferBuilder, VaultCell cell, int color) {
        renderCell(bufferBuilder, cell, color, 1.0f);
    }

    public static void renderCell(BufferBuilder bufferBuilder, VaultCell cell, int color, float scale) {
        var cellCenter = getCellCenter(cell);
        float mapX = cellCenter.x;
        float mapZ = cellCenter.y;
        float roomWidth = (mapRoomWidth / 2) * scale;
        float tunnelLen = (mapRoomWidth / 2) * scale;
        float startX;
        float startZ;
        float endX;
        float endZ;
        if (cell.cellType == CellType.CELLTYPE_TUNNEL_X || cell.cellType == CellType.CELLTYPE_TUNNEL_Z) {
            if (cell.cellType == CellType.CELLTYPE_TUNNEL_X) { // X facing
                startX = mapX - tunnelLen;
                startZ = mapZ - roomWidth / 2;
                endX = mapX + tunnelLen;
                endZ = mapZ + roomWidth / 2;
            } else { // Z facing
                startX = mapX - roomWidth / 2;
                startZ = mapZ - tunnelLen;
                endX = mapX + roomWidth / 2;
                endZ = mapZ + tunnelLen;
            }
        } else { // square
            startX = mapX - roomWidth;
            startZ = mapZ - roomWidth;
            endX = mapX + roomWidth;
            endZ = mapZ + roomWidth;
        }
        var minX = Math.min(startX, endX);
        var maxX = Math.max(startX, endX);
        var minZ = Math.min(startZ, endZ);
        var maxZ = Math.max(startZ, endZ);

        bufferBuilder.vertex(minX, maxZ, 0).color(color).endVertex();
        bufferBuilder.vertex(maxX, maxZ, 0).color(color).endVertex();
        bufferBuilder.vertex(maxX, minZ, 0).color(color).endVertex();
        bufferBuilder.vertex(minX, minZ, 0).color(color).endVertex();
    }

    public static void renderCellBorder(BufferBuilder bufferBuilder, VaultCell cell, int color) {
        var cellCenter = getCellCenter(cell);
        float mapX = cellCenter.x;
        float mapZ = cellCenter.y;

        int crop = ClientConfig.ICON_CROP.get();
        float scale = (16.0f - 2 * crop) / 16.0f;
        float halfSize = mapRoomWidth * scale;

        float minX = mapX - halfSize;
        float maxX = mapX + halfSize;
        float minZ = mapZ - halfSize;
        float maxZ = mapZ + halfSize;

        renderBorder(bufferBuilder,color, minX, minZ, maxX, maxZ, 1/8f*mapRoomWidth);
    }

    private static void renderGodAltarIndicatorDots(BufferBuilder bufferBuilder, VaultCell cell) {
        List<Integer> dotColors = VaultMap.getRoomGodAltarIndicatorColors(cell);
        List<net.minecraft.core.BlockPos> positions = VaultMap.getRoomGodAltarPositions(cell);
        if (dotColors.isEmpty() || positions.isEmpty()) return;

        float dotSize = Math.max(1.4f, mapRoomWidth * 0.12f);
        for (int i = 0; i < positions.size() && i < dotColors.size(); i++) {
            net.minecraft.core.BlockPos pos = positions.get(i);
            float exactRoomX = (float) (pos.getX() / 47.0 - 0.5);
            float exactRoomZ = (float) (pos.getZ() / 47.0 - 0.5);

            float arrowX;
            float arrowZ;
            int offsetX = ClientConfig.MAP_X_OFFSET.get();
            int offsetZ = ClientConfig.MAP_Y_OFFSET.get();
            if (playerCentricRender) {
                Vec2 rotated = rotateVector(exactRoomX - playerX, exactRoomZ - playerZ);
                arrowX = centerX + rotated.x * mapRoomWidth + offsetX;
                arrowZ = centerZ + rotated.y * mapRoomWidth + offsetZ;
            } else {
                Vec2 rotated = rotateVector(exactRoomX, exactRoomZ);
                arrowX = centerX + rotated.x * mapRoomWidth + offsetX;
                arrowZ = centerZ + rotated.y * mapRoomWidth + offsetZ;
            }

            float half = dotSize / 2.0f;
            float x0 = arrowX - half;
            float z0 = arrowZ - half;
            float x1 = arrowX + half;
            float z1 = arrowZ + half;
            int color = withAlpha(dotColors.get(i), 0.95f);

            bufferBuilder.vertex(x0, z1, 0).color(color).endVertex();
            bufferBuilder.vertex(x1, z1, 0).color(color).endVertex();
            bufferBuilder.vertex(x1, z0, 0).color(color).endVertex();
            bufferBuilder.vertex(x0, z0, 0).color(color).endVertex();
        }
    }

    private static void renderPylonIndicatorDots(BufferBuilder bufferBuilder, VaultCell cell) {
        List<Integer> dotColors = VaultMap.getRoomPylonIndicatorColors(cell);
        List<net.minecraft.core.BlockPos> positions = VaultMap.getRoomPylonPositions(cell);
        List<String> pylonTypes = VaultMap.getRoomPylonTypes(cell);
        if (dotColors.isEmpty() || positions.isEmpty()) return;

        float dotSize = Math.max(1.2f, mapRoomWidth * 0.10f);
        int offsetX = ClientConfig.MAP_X_OFFSET.get();
        int offsetZ = ClientConfig.MAP_Y_OFFSET.get();

        for (int i = 0; i < positions.size() && i < dotColors.size(); i++) {
            net.minecraft.core.BlockPos pos = positions.get(i);
            float exactRoomX = (float) (pos.getX() / 47.0 - 0.5);
            float exactRoomZ = (float) (pos.getZ() / 47.0 - 0.5);

            float arrowX;
            float arrowZ;
            if (playerCentricRender) {
                Vec2 rotated = rotateVector(exactRoomX - playerX, exactRoomZ - playerZ);
                arrowX = centerX + rotated.x * mapRoomWidth + offsetX;
                arrowZ = centerZ + rotated.y * mapRoomWidth + offsetZ;
            } else {
                Vec2 rotated = rotateVector(exactRoomX, exactRoomZ);
                arrowX = centerX + rotated.x * mapRoomWidth + offsetX;
                arrowZ = centerZ + rotated.y * mapRoomWidth + offsetZ;
            }

            // Time pylons get a larger dot size to stand out
            float finalDotSize = dotSize;
            String pylonType = (i < pylonTypes.size()) ? pylonTypes.get(i) : null;
            if ("time".equals(pylonType)) {
                finalDotSize = dotSize * 1.5f; // 50% larger for time pylons
            }

            float half = finalDotSize / 2.0f;
            float x0 = arrowX - half;
            float z0 = arrowZ - half;
            float x1 = arrowX + half;
            float z1 = arrowZ + half;
            int color = withAlpha(dotColors.get(i), 0.95f);

            bufferBuilder.vertex(x0, z1, 0).color(color).endVertex();
            bufferBuilder.vertex(x1, z1, 0).color(color).endVertex();
            bufferBuilder.vertex(x1, z0, 0).color(color).endVertex();
            bufferBuilder.vertex(x0, z0, 0).color(color).endVertex();
        }
    }

    private static void renderCakeIndicatorDots(BufferBuilder bufferBuilder, VaultCell cell) {
        List<Integer> dotColors = VaultMap.getRoomCakeIndicatorColors(cell);
        List<net.minecraft.core.BlockPos> positions = VaultMap.getRoomCakePositions(cell);
        if (dotColors.isEmpty() || positions.isEmpty()) return;

        float dotSize = Math.max(1.6f, mapRoomWidth * 0.12f);
        net.minecraft.core.BlockPos pos = positions.get(0);
        float exactRoomX = (float) (pos.getX() / 47.0 - 0.5);
        float exactRoomZ = (float) (pos.getZ() / 47.0 - 0.5);

        int offsetX = ClientConfig.MAP_X_OFFSET.get();
        int offsetZ = ClientConfig.MAP_Y_OFFSET.get();
        float arrowX;
        float arrowZ;
        if (playerCentricRender) {
            Vec2 rotated = rotateVector(exactRoomX - playerX, exactRoomZ - playerZ);
            arrowX = centerX + rotated.x * mapRoomWidth + offsetX;
            arrowZ = centerZ + rotated.y * mapRoomWidth + offsetZ;
        } else {
            Vec2 rotated = rotateVector(exactRoomX, exactRoomZ);
            arrowX = centerX + rotated.x * mapRoomWidth + offsetX;
            arrowZ = centerZ + rotated.y * mapRoomWidth + offsetZ;
        }

        float half = dotSize / 2.0f;
        float x0 = arrowX - half;
        float z0 = arrowZ - half;
        float x1 = arrowX + half;
        float z1 = arrowZ + half;
        int color = withAlpha(dotColors.get(0), 0.95f);

        bufferBuilder.vertex(x0, z1, 0).color(color).endVertex();
        bufferBuilder.vertex(x1, z1, 0).color(color).endVertex();
        bufferBuilder.vertex(x1, z0, 0).color(color).endVertex();
        bufferBuilder.vertex(x0, z0, 0).color(color).endVertex();
    }


    public static void renderTextureCell(BufferBuilder bufferBuilder, VaultCell cell) {
        var cellCenter = getCellCenter(cell);
        float mapX = cellCenter.x;
        float mapZ = cellCenter.y;

        int crop = ClientConfig.ICON_CROP.get();
        float scale = (16.0f - 2 * crop) / 16.0f;
        float halfSize = mapRoomWidth * scale;
        if (!cell.explored && isIdentifiedSpecialUndiscoveredRoom(cell)) {
            halfSize *= getRoomIconSizeMultiplier(cell);
        }

        float minX = mapX - halfSize;
        float maxX = mapX + halfSize;
        float minZ = mapZ - halfSize;
        float maxZ = mapZ + halfSize;

        float zeroOff = crop / 16f;
        float oneOff = 1.0F - crop / 16f;

        bufferBuilder.vertex(minX, maxZ, 0).uv(zeroOff, oneOff).endVertex();
        bufferBuilder.vertex(maxX, maxZ, 0).uv(oneOff, oneOff).endVertex();
        bufferBuilder.vertex(maxX, minZ, 0).uv(oneOff, zeroOff).endVertex();
        bufferBuilder.vertex(minX, minZ, 0).uv(zeroOff, zeroOff).endVertex();
    }

    public static void onWindowResize() {
        int w = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int h = Minecraft.getInstance().getWindow().getGuiScaledHeight();

        int mapSize = (int) (w * 0.25f);
        int baseMapRoomWidth = mapSize / 49;
        mapScaleMultiplier = (float) ClientConfig.MAP_SCALE.get() / 10;
        mapRoomWidth = ((float) baseMapRoomWidth * mapScaleMultiplier);

        updateAnchor();
    }

    public static void updateAnchor() {
        int width = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int height = Minecraft.getInstance().getWindow().getGuiScaledHeight();

        int sideMargin = 2*(int)mapRoomWidth + Minecraft.getInstance().font.lineHeight;
        switch (ClientConfig.MAP_X_ANCHOR.get()) {
            case 0 -> {
                if (playerCentricRender){
                    mapAnchorX = mapRoomWidth * cutoff + sideMargin;
                } else {
                    mapAnchorX = VaultMap.westSize * mapRoomWidth + mapRoomWidth + sideMargin;
                }
            }
            case 1 -> {
                if (playerCentricRender){
                    mapAnchorX = Math.max((float) width / 4, mapRoomWidth * cutoff + sideMargin);
                } else {
                    mapAnchorX = Math.max((float) width / 4, VaultMap.westSize * mapRoomWidth + mapRoomWidth + sideMargin);
                }
            }
            case 2 -> {
                mapAnchorX = (float) width / 2;
            }
            case 3 -> {
                if (playerCentricRender){
                    mapAnchorX = Math.min(width - ((float) width / 4), width - (mapRoomWidth * cutoff + sideMargin));
                } else {
                    mapAnchorX = Math.min(width - ((float) width / 4), width - (VaultMap.eastSize * mapRoomWidth + mapRoomWidth + sideMargin));
                }
            }
            case 4 -> {
                if (playerCentricRender){
                    mapAnchorX = width - (mapRoomWidth * cutoff + sideMargin);
                } else {
                    mapAnchorX = width - (VaultMap.eastSize * mapRoomWidth + mapRoomWidth + sideMargin);
                }
            }
        }

        switch (ClientConfig.MAP_Y_ANCHOR.get()) {
            case 0 -> {
                if (playerCentricRender){
                    mapAnchorZ = mapRoomWidth * cutoff + sideMargin;
                } else {
                    mapAnchorZ = VaultMap.northSize * mapRoomWidth + mapRoomWidth + sideMargin;
                }
            }
            case 1 -> {
                if (playerCentricRender){
                    mapAnchorZ = Math.max((float) height / 4, mapRoomWidth * cutoff + sideMargin);
                } else {
                    mapAnchorZ = Math.max((float) height / 4, VaultMap.northSize * mapRoomWidth + mapRoomWidth + sideMargin);
                }
            }
            case 2 -> {
                mapAnchorZ = (float) height / 2;
            }
            case 3 -> {
                if (playerCentricRender){
                    mapAnchorZ = Math.min(height - ((float) height / 4), height - (mapRoomWidth * cutoff + sideMargin));
                } else {
                    mapAnchorZ = Math.min(height - ((float) height / 4), height - (VaultMap.southSize * mapRoomWidth + mapRoomWidth + sideMargin));
                }
            }
            case 4 -> {
                if (playerCentricRender){
                    mapAnchorZ = height - (mapRoomWidth * cutoff + sideMargin);
                } else {
                    mapAnchorZ = height - (VaultMap.southSize * mapRoomWidth + mapRoomWidth + sideMargin);
                }
            }
        }

        centerX = mapAnchorX;
        centerZ = mapAnchorZ;
    }

    public static int parseColor(String hexColor) {
        return ColorUtil.parseHexColor(hexColor);
    }

    public static void prep() {
        playerCentricRender = ClientConfig.PLAYER_CENTRIC_RENDERING.get();
        cutoff = ClientConfig.PC_CUTOFF.get();

        onWindowResize();
        VaultMapper.LOGGER.info("prep ran");
        prepped = true;
    }

    public static void renderMapBorderPC(BufferBuilder bufferBuilder, int color) {
        if (!playerCentricRender || !ClientConfig.PC_BORDER.get()) return;
        float mapSize = (float) (((cutoff + 0.5) * mapRoomWidth) * 2);
        float mapSizeDelta = mapSize / 2;

        float startX = centerX - mapSizeDelta;
        float startZ = centerZ - mapSizeDelta;
        float endX = centerX + mapSizeDelta;
        float endZ  = centerZ + mapSizeDelta;

        renderBorder(bufferBuilder,color, startX, startZ, endX, endZ);
    }

    public static void renderBorder(BufferBuilder bufferBuilder, int color, float startX, float startZ, float endX, float endZ) {
        renderBorder(bufferBuilder, color, startX, startZ, endX, endZ, 1.0f);
    }

    public static void renderBorder(BufferBuilder bufferBuilder, int color, float startX, float startZ, float endX, float endZ, float lineWidth) {

        var minX = Math.min(startX, endX);
        var minXL = Math.min(startX, endX) + lineWidth;
        var minZ = Math.min(startZ, endZ);
        var minZL = Math.min(startZ, endZ) + lineWidth;

        var maxX = Math.max(startX, endX) - lineWidth;
        var maxXL = Math.max(startX, endX);
        var maxZ = Math.max(startZ, endZ) - lineWidth;
        var maxZL = Math.max(startZ, endZ);

        // Top border
        bufferBuilder.vertex(minX, minZL, 0).color(color).endVertex();
        bufferBuilder.vertex(maxXL, minZL, 0).color(color).endVertex();
        bufferBuilder.vertex(maxXL, minZ, 0).color(color).endVertex();
        bufferBuilder.vertex(minX, minZ, 0).color(color).endVertex();

        // Bottom border
        bufferBuilder.vertex(minX, maxZL, 0).color(color).endVertex();
        bufferBuilder.vertex(maxXL, maxZL, 0).color(color).endVertex();
        bufferBuilder.vertex(maxXL, maxZ, 0).color(color).endVertex();
        bufferBuilder.vertex(minX, maxZ, 0).color(color).endVertex();

        // Left border
        bufferBuilder.vertex(minX, maxZ, 0).color(color).endVertex();
        bufferBuilder.vertex(minXL, maxZ, 0).color(color).endVertex();
        bufferBuilder.vertex(minXL, minZL, 0).color(color).endVertex();
        bufferBuilder.vertex(minX, minZL, 0).color(color).endVertex();

        // Right border
        bufferBuilder.vertex(maxX, maxZ, 0).color(color).endVertex();
        bufferBuilder.vertex(maxXL, maxZ, 0).color(color).endVertex();
        bufferBuilder.vertex(maxXL, minZL, 0).color(color).endVertex();
        bufferBuilder.vertex(maxX, minZL, 0).color(color).endVertex();
    }

    public static Vec2 getCellCenter(VaultCell cell) {
        if (playerCentricRender){
            Vec2 rotated = rotateVector(cell.x - playerX, cell.z - playerZ);
            return new Vec2(
                    centerX + rotated.x * mapRoomWidth + ClientConfig.MAP_X_OFFSET.get(),
                    centerZ + rotated.y * mapRoomWidth + ClientConfig.MAP_Y_OFFSET.get()
            );
        }
        Vec2 rotated = rotateVector(cell.x, cell.z);
        return new Vec2(
                centerX + rotated.x * mapRoomWidth + ClientConfig.MAP_X_OFFSET.get(),
                centerZ + rotated.y * mapRoomWidth + ClientConfig.MAP_Y_OFFSET.get()
        );
    }

    private static float getMapRotationDegrees() {
        return VaultMap.getMapRotationQuarterTurns() * 90.0f;
    }

    private static Vec2 rotateVector(float x, float z) {
        int turns = Math.floorMod(VaultMap.getMapRotationQuarterTurns(), 4);
        return switch (turns) {
            case 1 -> new Vec2(-z, x);
            case 2 -> new Vec2(-x, -z);
            case 3 -> new Vec2(z, -x);
            default -> new Vec2(x, z);
        };
    }

    private static void renderBossTypeLetter(PoseStack poseStack, VaultCell cell, String letter) {
        Vec2 center = getCellCenter(cell);
        float iconScale = getRoomIconSizeMultiplier(cell);
        float iconHalfSize = mapRoomWidth * ((16.0f - 2 * ClientConfig.ICON_CROP.get()) / 16.0f) * iconScale;
        float targetHeight = Math.max(5.0f, iconHalfSize * 1.05f);

        var font = Minecraft.getInstance().font;
        float textScale = targetHeight / Math.max(1.0f, font.lineHeight);

        poseStack.pushPose();
        poseStack.translate(center.x, center.y, 0);
        poseStack.scale(textScale, textScale, 1.0f);

        int baseY = -(font.lineHeight / 2);
        GuiComponent.drawCenteredString(poseStack, font, letter, -1, baseY, 0xFF000000);
        GuiComponent.drawCenteredString(poseStack, font, letter, 1, baseY, 0xFF000000);
        GuiComponent.drawCenteredString(poseStack, font, letter, 0, baseY - 1, 0xFF000000);
        GuiComponent.drawCenteredString(poseStack, font, letter, 0, baseY + 1, 0xFF000000);
        GuiComponent.drawCenteredString(poseStack, font, letter, 0, baseY, 0xFFFFFFFF);

        poseStack.popPose();
    }

    private static float getRoomIconSizeMultiplier(VaultCell cell) {
        if (!cell.explored && isIdentifiedSpecialUndiscoveredRoom(cell)) {
            int percent = ClientConfig.IDENTIFIED_SPECIAL_UNDISCOVERED_ICON_SCALE.get();
            return Math.max(0.30f, Math.min(1.0f, percent / 100.0f));
        }
        return 1.0f;
    }


}
