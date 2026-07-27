package com.nodiumhosting.vaultmapper.map.special;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.nodiumhosting.vaultmapper.config.ClientConfig;
import com.nodiumhosting.vaultmapper.map.VaultMap;
import com.nodiumhosting.vaultmapper.proto.RoomType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.TextComponent;

public final class VaultRoomTileRenderer {
    private VaultRoomTileRenderer() {
    }

    public static void render(PoseStack poseStack) {
        if (!ClientConfig.SHOW_ROOM_TILE.get()) return;
        var cell = VaultMap.getCurrentRoom();
        if (cell == null) return;

        int guiWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int guiHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();

        int xOffset = ClientConfig.ROOM_TILE_X_OFFSET.get();
        int yOffset = ClientConfig.ROOM_TILE_Y_OFFSET.get();
        int xAnchor = ClientConfig.ROOM_TILE_X_ANCHOR.get();
        int yAnchor = ClientConfig.ROOM_TILE_Y_ANCHOR.get();
        float scale = Math.max(0.3f, Math.min(3.0f, ClientConfig.ROOM_TILE_SCALE.get() / 10.0f));

        String roomName = cell.roomName;
        if (roomName == null || roomName.isEmpty()) {
            roomName = cell.roomType.name();
        }
        String roomTypeLabel = getRoomTypeLabel(cell.roomType);
        String coords = "[" + cell.x + ", " + cell.z + "]";

        var font = Minecraft.getInstance().font;

        int nameWidth = font.width(roomName);
        int typeWidth = font.width(roomTypeLabel);
        int coordsWidth = font.width(coords);
        int maxLineWidth = Math.max(Math.max(nameWidth, typeWidth), coordsWidth);

        int lineHeight = font.lineHeight;
        int padding = 4;
        int totalTextHeight = lineHeight * 3 + padding * 2;

        int boxX = computeAnchorX(xAnchor, guiWidth, maxLineWidth, xOffset, scale);
        int boxY = computeAnchorY(yAnchor, guiHeight, totalTextHeight, yOffset, scale);

        int boxW = Math.round((maxLineWidth + padding * 2) * scale);
        int boxH = Math.round(totalTextHeight * scale);

        drawBackground(poseStack, boxX, boxY, boxW, boxH);

        poseStack.pushPose();
        poseStack.translate(boxX, boxY, 0);
        poseStack.scale(scale, scale, 1.0f);

        int textColor = 0xFFFFFFFF;
        int dimColor = 0xFFAAAAAA;
        int textX = (maxLineWidth - nameWidth) / 2;
        int textY = padding;

        font.draw(poseStack, roomName, textX, textY, textColor);
        textY += lineHeight;

        font.draw(poseStack, roomTypeLabel, (maxLineWidth - typeWidth) / 2, textY, dimColor);
        textY += lineHeight;

        font.draw(poseStack, coords, (maxLineWidth - coordsWidth) / 2, textY, dimColor);

        poseStack.popPose();
    }

    private static void drawBackground(PoseStack poseStack, int x, int y, int width, int height) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        int bgColor = 0x88000000;
        int borderColor = 0xFF444444;

        float bx = x;
        float by = y;
        float bw = width;
        float bh = height;

        bufferBuilder.vertex(bx, by + bh, 0).color(bgColor).endVertex();
        bufferBuilder.vertex(bx + bw, by + bh, 0).color(bgColor).endVertex();
        bufferBuilder.vertex(bx + bw, by, 0).color(bgColor).endVertex();
        bufferBuilder.vertex(bx, by, 0).color(bgColor).endVertex();

        bufferBuilder.end();
        BufferUploader.end(bufferBuilder);

        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        float borderW = 1.0f;
        bufferBuilder.vertex(bx, by + bh, 0).color(borderColor).endVertex();
        bufferBuilder.vertex(bx + bw, by + bh, 0).color(borderColor).endVertex();
        bufferBuilder.vertex(bx + bw, by + bh - borderW, 0).color(borderColor).endVertex();
        bufferBuilder.vertex(bx, by + bh - borderW, 0).color(borderColor).endVertex();
        bufferBuilder.end();
        BufferUploader.end(bufferBuilder);

        RenderSystem.disableBlend();
    }

    private static int computeAnchorX(int anchor, int guiWidth, int contentWidth, int offset, float scale) {
        int scaledWidth = Math.round(contentWidth * scale);
        return switch (anchor) {
            case 0 -> offset;
            case 1 -> guiWidth / 4 - scaledWidth / 2 + offset;
            case 2 -> guiWidth / 2 - scaledWidth / 2 + offset;
            case 3 -> 3 * guiWidth / 4 - scaledWidth / 2 + offset;
            case 4 -> guiWidth - scaledWidth - offset;
            default -> guiWidth / 2 - scaledWidth / 2;
        };
    }

    private static int computeAnchorY(int anchor, int guiHeight, int contentHeight, int offset, float scale) {
        int scaledHeight = Math.round(contentHeight * scale);
        return switch (anchor) {
            case 0 -> offset;
            case 1 -> guiHeight / 4 - scaledHeight / 2 + offset;
            case 2 -> guiHeight / 2 - scaledHeight / 2 + offset;
            case 3 -> 3 * guiHeight / 4 - scaledHeight / 2 + offset;
            case 4 -> guiHeight - scaledHeight - offset;
            default -> guiHeight / 2 - scaledHeight / 2;
        };
    }

    private static String getRoomTypeLabel(RoomType type) {
        return switch (type) {
            case ROOMTYPE_START -> "Start Room";
            case ROOMTYPE_OMEGA -> "Omega Room";
            case ROOMTYPE_CHALLENGE -> "Challenge Room";
            case ROOMTYPE_ORE -> "Ore Room";
            case ROOMTYPE_RESOURCE -> "Resource Room";
            case ROOMTYPE_BASIC -> "Room";
            case ROOMTYPE_UNKNOWN -> "Unknown";
            default -> "Room";
        };
    }
}
