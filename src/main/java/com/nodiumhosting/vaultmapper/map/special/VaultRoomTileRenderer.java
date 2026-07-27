package com.nodiumhosting.vaultmapper.map.special;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.nodiumhosting.vaultmapper.config.ClientConfig;
import com.nodiumhosting.vaultmapper.map.VaultMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;

import java.util.List;

public final class VaultRoomTileRenderer {
    private static final int ROOM_SIZE = 47;

    private VaultRoomTileRenderer() {
    }

    public static void render(PoseStack poseStack) {
        if (!ClientConfig.SHOW_ROOM_TILE.get()) return;
        var cell = VaultMap.getCurrentRoom();
        if (cell == null) return;

        int guiWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();

        int xOffset = ClientConfig.ROOM_TILE_X_OFFSET.get();
        int yOffset = ClientConfig.ROOM_TILE_Y_OFFSET.get();
        int tileSize = Math.max(20, Math.min(200, ClientConfig.ROOM_TILE_SCALE.get()));

        int tileX = xOffset + 2;
        int tileY = yOffset + 2;

        int roomColor = parseColor(VaultMap.getCellColor(cell));

        drawTile(poseStack, tileX, tileY, tileSize, roomColor, cell.x, cell.z);
    }

    private static void drawTile(PoseStack poseStack, int x, int y, int size, int roomColor, int cellX, int cellZ) {
        int borderColor = 0xFF888888;
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
        bufferBuilder.vertex(innerX, innerY + innerSize, 0).color(roomColor).endVertex();
        bufferBuilder.vertex(innerX + innerSize, innerY + innerSize, 0).color(roomColor).endVertex();
        bufferBuilder.vertex(innerX + innerSize, innerY, 0).color(roomColor).endVertex();
        bufferBuilder.vertex(innerX, innerY, 0).color(roomColor).endVertex();
        bufferBuilder.end();
        BufferUploader.end(bufferBuilder);

        int roomWorldX = cellX * ROOM_SIZE;
        int roomWorldZ = cellZ * ROOM_SIZE;

        renderFeatureDots(bufferBuilder, innerX, innerY, innerSize, roomWorldX, roomWorldZ);

        RenderSystem.disableBlend();
    }

    private static void renderFeatureDots(BufferBuilder bufferBuilder, int originX, int originY, int tileSize, int roomWorldX, int roomWorldZ) {
        var cell = VaultMap.getCurrentRoom();
        if (cell == null) return;

        renderDots(bufferBuilder, originX, originY, tileSize, roomWorldX, roomWorldZ,
                VaultMap.getRoomGodAltarPositions(cell), VaultMap.getRoomGodAltarIndicatorColors(cell), 3.0f);

        renderDots(bufferBuilder, originX, originY, tileSize, roomWorldX, roomWorldZ,
                VaultMap.getRoomCakePositions(cell), VaultMap.getRoomCakeIndicatorColors(cell), 3.5f);

        renderDots(bufferBuilder, originX, originY, tileSize, roomWorldX, roomWorldZ,
                VaultMap.getRoomPylonPositions(cell), VaultMap.getRoomPylonIndicatorColors(cell), 2.5f);
    }

    private static void renderDots(BufferBuilder bufferBuilder, int originX, int originY, int tileSize,
                                    int roomWorldX, int roomWorldZ,
                                    List<BlockPos> positions, List<Integer> colors, float dotRadius) {
        if (positions.isEmpty() || colors.isEmpty()) return;

        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (int i = 0; i < positions.size() && i < colors.size(); i++) {
            BlockPos pos = positions.get(i);
            float relX = (float) (pos.getX() - roomWorldX) / ROOM_SIZE;
            float relZ = (float) (pos.getZ() - roomWorldZ) / ROOM_SIZE;

            float dotX = originX + relX * tileSize;
            float dotY = originY + relZ * tileSize;

            int color = 0xCC000000 | (colors.get(i) & 0x00FFFFFF);

            float half = dotRadius;
            bufferBuilder.vertex(dotX - half, dotY + half, 0).color(color).endVertex();
            bufferBuilder.vertex(dotX + half, dotY + half, 0).color(color).endVertex();
            bufferBuilder.vertex(dotX + half, dotY - half, 0).color(color).endVertex();
            bufferBuilder.vertex(dotX - half, dotY - half, 0).color(color).endVertex();
        }

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
