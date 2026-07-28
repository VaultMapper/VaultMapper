package com.nodiumhosting.vaultmapper.map.special;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.nodiumhosting.vaultmapper.config.ClientConfig;
import com.nodiumhosting.vaultmapper.map.VaultCell;
import com.nodiumhosting.vaultmapper.map.VaultMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;

import java.util.List;

public final class VaultRoomTileRenderer {
    private static final int ROOM_SIZE = 47;
    private static final int BG_COLOR = 0xFF1A1A2E;

    private VaultRoomTileRenderer() {
    }

    public static void render(PoseStack poseStack) {
        if (!ClientConfig.SHOW_ROOM_TILE.get()) return;
        var cell = VaultMap.getCurrentRoom();
        if (cell == null) return;

        int xOffset = ClientConfig.ROOM_TILE_X_OFFSET.get();
        int yOffset = ClientConfig.ROOM_TILE_Y_OFFSET.get();
        int tileSize = Math.max(20, Math.min(200, ClientConfig.ROOM_TILE_SCALE.get()));

        drawTile(poseStack, xOffset, yOffset, tileSize, cell);
    }

    private static void drawTile(PoseStack poseStack, int x, int y, int size, VaultCell cell) {
        int borderColor = 0xFF555555;
        int innerX = x + 1;
        int innerY = y + 1;
        int innerSize = size - 2;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();

        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bufferBuilder.vertex(x, y + size, 0).color(borderColor).endVertex();
        bufferBuilder.vertex(x + size, y + size, 0).color(borderColor).endVertex();
        bufferBuilder.vertex(x + size, y, 0).color(borderColor).endVertex();
        bufferBuilder.vertex(x, y, 0).color(borderColor).endVertex();
        bufferBuilder.end();
        BufferUploader.end(bufferBuilder);

        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bufferBuilder.vertex(innerX, innerY + innerSize, 0).color(BG_COLOR).endVertex();
        bufferBuilder.vertex(innerX + innerSize, innerY + innerSize, 0).color(BG_COLOR).endVertex();
        bufferBuilder.vertex(innerX + innerSize, innerY, 0).color(BG_COLOR).endVertex();
        bufferBuilder.vertex(innerX, innerY, 0).color(BG_COLOR).endVertex();
        bufferBuilder.end();
        BufferUploader.end(bufferBuilder);

        int roomWorldX = cell.x * ROOM_SIZE;
        int roomWorldZ = cell.z * ROOM_SIZE;

        renderFeatureDots(poseStack, bufferBuilder, innerX, innerY, innerSize, roomWorldX, roomWorldZ);
        renderPlayerArrow(bufferBuilder, innerX, innerY, innerSize, roomWorldX, roomWorldZ);

        RenderSystem.disableBlend();
    }

    private static void renderFeatureDots(PoseStack poseStack, BufferBuilder bufferBuilder, int originX, int originY, int tileSize, int roomWorldX, int roomWorldZ) {
        var cell = VaultMap.getCurrentRoom();
        if (cell == null) return;

        renderSquareDots(bufferBuilder, originX, originY, tileSize, roomWorldX, roomWorldZ,
                VaultMap.getRoomGodAltarPositions(cell), VaultMap.getRoomGodAltarIndicatorColors(cell), 0.045f);

        renderPylonDiamonds(bufferBuilder, originX, originY, tileSize, roomWorldX, roomWorldZ,
                VaultMap.getRoomPylonPositions(cell), 0.04f);

        renderCakeIndicator(poseStack, originX, originY, tileSize, roomWorldX, roomWorldZ, cell);
    }

    private static void renderSquareDots(BufferBuilder bufferBuilder, int originX, int originY, int tileSize,
                                          int roomWorldX, int roomWorldZ,
                                          List<BlockPos> positions, List<Integer> colors, float sizeFactor) {
        if (positions.isEmpty() || colors.isEmpty()) return;

        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        float half = Math.max(1.5f, tileSize * sizeFactor / 2.0f);

        for (int i = 0; i < positions.size() && i < colors.size(); i++) {
            BlockPos pos = positions.get(i);
            float relX = (float) (pos.getX() - roomWorldX) / ROOM_SIZE;
            float relZ = (float) (pos.getZ() - roomWorldZ) / ROOM_SIZE;

            float dotX = originX + relX * tileSize;
            float dotY = originY + relZ * tileSize;

            int color = 0xDD000000 | (colors.get(i) & 0x00FFFFFF);

            bufferBuilder.vertex(dotX - half, dotY + half, 0).color(color).endVertex();
            bufferBuilder.vertex(dotX + half, dotY + half, 0).color(color).endVertex();
            bufferBuilder.vertex(dotX + half, dotY - half, 0).color(color).endVertex();
            bufferBuilder.vertex(dotX - half, dotY - half, 0).color(color).endVertex();
        }

        bufferBuilder.end();
        BufferUploader.end(bufferBuilder);
    }

    private static void renderPylonDiamonds(BufferBuilder bufferBuilder, int originX, int originY, int tileSize,
                                              int roomWorldX, int roomWorldZ,
                                              List<BlockPos> positions, float sizeFactor) {
        if (positions.isEmpty()) return;

        float halfSize = Math.max(2.0f, tileSize * sizeFactor);
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (BlockPos pos : positions) {
            float relX = (float) (pos.getX() - roomWorldX) / ROOM_SIZE;
            float relZ = (float) (pos.getZ() - roomWorldZ) / ROOM_SIZE;

            float cx = originX + relX * tileSize;
            float cy = originY + relZ * tileSize;

            int pylonColor = 0xDD00FFFF;

            bufferBuilder.vertex(cx, cy - halfSize, 0).color(pylonColor).endVertex();
            bufferBuilder.vertex(cx + halfSize, cy, 0).color(pylonColor).endVertex();
            bufferBuilder.vertex(cx, cy + halfSize, 0).color(pylonColor).endVertex();
            bufferBuilder.vertex(cx - halfSize, cy, 0).color(pylonColor).endVertex();
        }

        bufferBuilder.end();
        BufferUploader.end(bufferBuilder);
    }

    private static void renderCakeIndicator(PoseStack poseStack, int originX, int originY, int tileSize,
                                             int roomWorldX, int roomWorldZ, VaultCell cell) {
        List<BlockPos> cakePositions = VaultMap.getRoomCakePositions(cell);
        if (cakePositions.isEmpty()) return;

        BlockPos cakePos = cakePositions.get(0);
        float relX = (float) (cakePos.getX() - roomWorldX) / ROOM_SIZE;
        float relZ = (float) (cakePos.getZ() - roomWorldZ) / ROOM_SIZE;

        int cx = originX + (int) (relX * tileSize);
        int cy = originY + (int) (relZ * tileSize);

        float cakeRadius = Math.max(3.0f, tileSize * 0.05f);
        int segments = 12;

        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);

        int cakeColor = 0xCCFF6666;
        bufferBuilder.vertex(cx, cy, 0).color(cakeColor).endVertex();

        for (int i = 0; i <= segments; i++) {
            double angle = 2.0 * Math.PI * i / segments;
            float px = cx + (float) (Math.cos(angle) * cakeRadius);
            float py = cy + (float) (Math.sin(angle) * cakeRadius);
            bufferBuilder.vertex(px, py, 0).color(cakeColor).endVertex();
        }

        bufferBuilder.end();
        BufferUploader.end(bufferBuilder);

        var font = Minecraft.getInstance().font;
        String cakeLabel = "C";
        int labelX = cx - font.width(cakeLabel) / 2;
        int labelY = cy - font.lineHeight / 2;
        font.draw(poseStack, cakeLabel, labelX, labelY, 0xFFFFFFFF);
    }

    private static void renderPlayerArrow(BufferBuilder bufferBuilder, int originX, int originY, int tileSize, int roomWorldX, int roomWorldZ) {
        var player = Minecraft.getInstance().player;
        if (player == null) return;

        float relX = (float) (player.getX() - roomWorldX) / ROOM_SIZE;
        float relZ = (float) (player.getZ() - roomWorldZ) / ROOM_SIZE;

        float ax = originX + relX * tileSize;
        float ay = originY + relZ * tileSize;

        float arrowSize = Math.max(3.0f, tileSize * 0.07f);
        float yawRad = (float) Math.toRadians(player.getYHeadRot());

        float tipX = ax + (float) (Math.sin(yawRad) * arrowSize);
        float tipY = ay + (float) (Math.cos(yawRad) * arrowSize);
        float leftX = ax + (float) (Math.sin(yawRad + 2.5f) * arrowSize * 0.5f);
        float leftY = ay + (float) (Math.cos(yawRad + 2.5f) * arrowSize * 0.5f);
        float rightX = ax + (float) (Math.sin(yawRad - 2.5f) * arrowSize * 0.5f);
        float rightY = ay + (float) (Math.cos(yawRad - 2.5f) * arrowSize * 0.5f);

        int arrowColor = parseColor(ClientConfig.POINTER_COLOR.get());

        bufferBuilder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        bufferBuilder.vertex(tipX, tipY, 0).color(arrowColor).endVertex();
        bufferBuilder.vertex(rightX, rightY, 0).color(arrowColor).endVertex();
        bufferBuilder.vertex(leftX, leftY, 0).color(arrowColor).endVertex();
        bufferBuilder.end();
        BufferUploader.end(bufferBuilder);
    }

    private static int parseColor(String hexColor) {
        if (hexColor == null || hexColor.isEmpty()) return 0xFF0000FF;
        try {
            long c = Long.decode(hexColor);
            return 0xFF000000 | ((int) c & 0x00FFFFFF);
        } catch (NumberFormatException e) {
            return 0xFF0000FF;
        }
    }
}
