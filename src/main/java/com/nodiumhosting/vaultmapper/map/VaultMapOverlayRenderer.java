package com.nodiumhosting.vaultmapper.map;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.nodiumhosting.vaultmapper.VaultMapper;
import com.nodiumhosting.vaultmapper.config.ClientConfig;
import com.nodiumhosting.vaultmapper.proto.CellType;
import com.nodiumhosting.vaultmapper.util.MapRoomIconUtil;
import iskallia.vault.core.vault.ClientVaults;
import iskallia.vault.core.vault.Vault;
import iskallia.vault.core.vault.objective.HeraldObjective;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec2;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.UsernameCache;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

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
        Optional<Vault> vaultOpt = ClientVaults.getActive();
        if (vaultOpt.isPresent()) {
            Vault vault = vaultOpt.get();
            if (!vault.get(Vault.OBJECTIVES).getAll(HeraldObjective.class).isEmpty()) {
                return;
            }
        }
        if (!prepped) prep();

        int offsetX = ClientConfig.MAP_X_OFFSET.get();
        int offsetZ = ClientConfig.MAP_Y_OFFSET.get();

        if (VaultMap.currentRoom != null) {
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

        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        RenderSystem.enableBlend();
        RenderSystem.disableTexture();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        // map border if player centric render and border enabled in config
        if (playerCentricRender && ClientConfig.PC_BORDER.get()) {
            renderMapBorderPC(bufferBuilder, 0xDD808080);
        }

        // Tunnel map
        VaultMap.cells.stream().filter((cell) -> cell.cellType == CellType.CELLTYPE_TUNNEL_X || cell.cellType == CellType.CELLTYPE_TUNNEL_Z).forEach((cell) -> {
            renderCell(bufferBuilder, cell, parseColor(VaultMap.getCellColor(cell)));
        });

        // cell map
        VaultMap.cells.stream().filter((cell) -> cell.cellType == CellType.CELLTYPE_ROOM).forEach((cell) -> {
            renderCell(bufferBuilder, cell, parseColor(VaultMap.getCellColor(cell)));
        });

        bufferBuilder.end();
        BufferUploader.end(bufferBuilder); // render the map

        RenderSystem.enableTexture();
        RenderSystem.disableBlend();

        // render icons
        if (ClientConfig.SHOW_ROOM_ICONS.get()) {
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.disableBlend();
            VaultMap.cells.stream().filter((cell) -> cell.cellType == CellType.CELLTYPE_ROOM).forEach((cell) -> {
                if (cell.roomName == null || cell.roomName.equals("")) {
                    cell.roomName = cell.roomType.name();
                }

                try {
                    ResourceLocation icon = MapRoomIconUtil.getIconForRoom(cell.roomName);
                    if (icon == null) {
                        VaultMapper.LOGGER.error("Icon {} not found for room: {}", icon, cell.roomName);
                        return;
                    }
                    RenderSystem.setShaderTexture(0, icon);
                    bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);

                    //Gui.blit(event.getMatrixStack(), (int) (centerX + cell.x * mapRoomWidth + offsetX), (int) (centerZ + cell.z * mapRoomWidth + offsetZ), 0, 0, (int) mapRoomWidth, (int) mapRoomWidth, 16, 16);
                    //VaultMapper.LOGGER.info(String.valueOf(mapRoomWidth));
                    renderTextureCell(bufferBuilder, cell);
                } catch (Exception e) {
                    VaultMapper.LOGGER.error("Failed to render icon for room: " + cell.roomName);
                }

                bufferBuilder.end();
                BufferUploader.end(bufferBuilder);
            });

            RenderSystem.enableBlend();
            RenderSystem.disableTexture();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);

            bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

            // cell map
            VaultMap.cells.stream().filter((cell) -> cell.cellType == CellType.CELLTYPE_ROOM && (cell.inscripted || cell.marked)).forEach((cell) -> {
                renderCellBorder(bufferBuilder, cell, parseColor(VaultMap.getCellColor(cell)));
            });

            bufferBuilder.end();
            BufferUploader.end(bufferBuilder); // render the map
        }

        bufferBuilder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        // player thingies
        VaultMap.players.forEach((name, data) -> renderPlayerArrow(bufferBuilder, data));
        bufferBuilder.end();
        BufferUploader.end(bufferBuilder);

        // player thingy TODO: Might need to adjust this thing as the previous loop handles it
        var currentPlayer = new VaultMap.MapPlayer();
        currentPlayer.x = playerX;
        currentPlayer.y = playerZ;
        currentPlayer.yaw = Minecraft.getInstance().player.getYHeadRot();
        currentPlayer.color = ClientConfig.POINTER_COLOR.get();
        bufferBuilder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        renderPlayerArrow(bufferBuilder, currentPlayer);

        bufferBuilder.end();
        BufferUploader.end(bufferBuilder);

        RenderSystem.enableTexture();
        RenderSystem.disableBlend();

        if (Minecraft.getInstance().options.keyPlayerList.isDown()) {
            VaultMap.players.forEach((name, data) -> renderPlayerName(event.getMatrixStack(), name, data));
        }
    }

    private static void renderPlayerName(PoseStack posestack, String name, VaultMap.MapPlayer data) {
        int offsetX = ClientConfig.MAP_X_OFFSET.get();
        int offsetZ = ClientConfig.MAP_Y_OFFSET.get();
        float arrowX;
        float arrowZ;
        if (playerCentricRender) {
            if (abs(data.x - playerX) > cutoff || abs(data.y - playerZ) > cutoff) return;
            arrowX = centerX + (data.x - playerX) * mapRoomWidth + offsetX;
            arrowZ = centerZ + (data.y - playerZ) * mapRoomWidth + offsetZ;
        } else {
            arrowX = centerX + data.x * mapRoomWidth + offsetX;
            arrowZ = centerZ + data.y * mapRoomWidth + offsetZ;
        }

        try {
            var id = UUID.fromString(name);
            String username = UsernameCache.getLastKnownUsername(id);
            if (username == null || username.isEmpty()) {
                return;
            }
            name = username;

        } catch (IllegalArgumentException e) {
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
            arrowX = centerX + (data.x - playerX) * mapRoomWidth + offsetX; //breaks with certain high values, god knows why
            arrowZ = centerZ + (data.y - playerZ) * mapRoomWidth + offsetZ; //breaks with certain high values, god knows why
        } else {
            arrowX = centerX + data.x * mapRoomWidth + offsetX; //breaks with certain high values, god knows why
            arrowZ = centerZ + data.y * mapRoomWidth + offsetZ; //breaks with certain high values, god knows why
        }
        var triag = getRotatedTriangle(data.yaw);
        int color = parseColor(data.color);
        bufferBuilder.vertex(triag.get(0) + arrowX, triag.get(1) + arrowZ, 0).color(color).endVertex();
        bufferBuilder.vertex(triag.get(2) + arrowX, triag.get(3) + arrowZ, 0).color(color).endVertex();
        bufferBuilder.vertex(triag.get(4) + arrowX, triag.get(5) + arrowZ, 0).color(color).endVertex();
    }

    private static ArrayList<Float> getRotatedTriangle(float yaw) { // returns three points that make a rotated triangle when added with mapx,z
        double arrowScale = ClientConfig.ARROW_SCALE.get() * 0.03f;
        double x1 = -3 * mapRoomWidth * arrowScale + mapRoomWidth / 2;
        double y1 = -2 * mapRoomWidth * arrowScale;
        double x2 = -3 * mapRoomWidth * arrowScale + mapRoomWidth / 2;
        double y2 = 2 * mapRoomWidth * arrowScale;
        double x3 = 3 * mapRoomWidth * arrowScale + mapRoomWidth / 2;
        double y3 = 0 * mapRoomWidth * arrowScale;

        double cx = (x1 + x2) / 2; // centers to rotate about
        double cy = (y1 + y2) / 2;
        float radangle = (float) Math.toRadians(yaw + 90);

        double[] rotatedVert1 = rotatePoint(x1, y1, cx, cy, radangle);
        double[] rotatedVert2 = rotatePoint(x2, y2, cx, cy, radangle);
        double[] rotatedVert3 = rotatePoint(x3, y3, cx, cy, radangle);

        var retlist = new ArrayList<Float>();
        retlist.add((float) rotatedVert1[0] - (float)cx);
        retlist.add((float) rotatedVert1[1] - (float)cy);
        retlist.add((float) rotatedVert2[0] - (float)cx);
        retlist.add((float) rotatedVert2[1] - (float)cy);
        retlist.add((float) rotatedVert3[0] - (float)cx);
        retlist.add((float) rotatedVert3[1] - (float)cy);
        return retlist;
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

    public static void renderCell(BufferBuilder bufferBuilder, VaultCell cell, int color) {
        if (cell.cellType != CellType.CELLTYPE_UNKNOWN) {
            if (cell.inscripted && !cell.explored && !ClientConfig.SHOW_INSCRIPTIONS.get())
                return;
            if (playerCentricRender && (abs(cell.x - playerX) > cutoff || abs(cell.z - playerZ) > cutoff)) return;
            var cellCenter = getCellCenter(cell);
            float mapX = cellCenter.x;
            float mapZ = cellCenter.y;
            float roomWidth = mapRoomWidth / 2;
            float tunnelLen = mapRoomWidth / 2;
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
    }

    public static void renderCellBorder(BufferBuilder bufferBuilder, VaultCell cell, int color) {
        if (cell.cellType != CellType.CELLTYPE_ROOM) {
            return;
        }

        if (cell.inscripted && !cell.explored && !ClientConfig.SHOW_INSCRIPTIONS.get())
            return;

        if (playerCentricRender && (abs(cell.x - playerX) > cutoff || abs(cell.z - playerZ) > cutoff)) return;
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


    public static void renderTextureCell(BufferBuilder bufferBuilder, VaultCell cell) {
        if (cell.cellType == CellType.CELLTYPE_ROOM) {
            if (cell.inscripted && !cell.explored && !ClientConfig.SHOW_INSCRIPTIONS.get()) return;
            if (playerCentricRender && (abs(cell.x - playerX) > cutoff || abs(cell.z - playerZ) > cutoff)) return;
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

            float zeroOff = crop / 16f;
            float oneOff = 1.0F - crop / 16f;

            bufferBuilder.vertex(minX, maxZ, 0).uv(zeroOff, oneOff).endVertex();
            bufferBuilder.vertex(maxX, maxZ, 0).uv(oneOff, oneOff).endVertex();
            bufferBuilder.vertex(maxX, minZ, 0).uv(oneOff, zeroOff).endVertex();
            bufferBuilder.vertex(minX, minZ, 0).uv(zeroOff, zeroOff).endVertex();
        }
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
            return new Vec2(
                centerX + (cell.x - playerX) * mapRoomWidth + ClientConfig.MAP_X_OFFSET.get(),
                centerZ + (cell.z - playerZ) * mapRoomWidth + ClientConfig.MAP_Y_OFFSET.get()
            );
        }
        return new Vec2(
            centerX + (cell.x) * mapRoomWidth + ClientConfig.MAP_X_OFFSET.get(),
                centerZ + (cell.z) * mapRoomWidth + ClientConfig.MAP_Y_OFFSET.get()
        );
    }

}