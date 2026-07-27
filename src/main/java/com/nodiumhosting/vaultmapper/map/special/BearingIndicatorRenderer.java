package com.nodiumhosting.vaultmapper.map.special;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.nodiumhosting.vaultmapper.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.TextComponent;

public final class BearingIndicatorRenderer {
    private static final int BEARING_RING_RADIUS = 28;
    private static final int BEARING_RING_SEGMENTS = 32;

    private BearingIndicatorRenderer() {
    }

    public static void render(PoseStack poseStack, String label, BlockPos targetPos) {
        SpecialFeatureBearing bearing = SpecialFeatureBearingCalculator.calculate(targetPos);
        if (bearing == null) return;

        Minecraft mc = Minecraft.getInstance();
        int centerX = mc.getWindow().getGuiScaledWidth() / 2;
        int centerY = mc.getWindow().getGuiScaledHeight() / 2;

        drawBearingRing(poseStack, centerX, centerY);

        float arrowAngle = (float) Math.toRadians(bearing.yawDegrees);
        float arrowLen = BEARING_RING_RADIUS - 4;
        float endX = centerX + (float) (Math.sin(arrowAngle) * arrowLen);
        float endY = centerY - (float) (Math.cos(arrowAngle) * arrowLen);

        drawArrowLine(poseStack, centerX, centerY, endX, endY, bearing.cardinalDirection);

        int labelY = centerY + BEARING_RING_RADIUS + 4;
        String distText = String.format("%.1f m", bearing.distance);
        String heightChar = bearing.isAbove ? "\u25B2" : bearing.isBelow ? "\u25BC" : "\u25C6";
        int heightColor = bearing.isAbove ? 0xFF55FF55 : bearing.isBelow ? 0xFFFF5555 : 0xFFFFDD55;

        String infoLine = label + " " + distText;
        var font = mc.font;
        GuiComponent.drawCenteredString(poseStack, font, infoLine, centerX, labelY, 0xFFFFFFFF);
        font.draw(poseStack, heightChar, centerX + font.width(infoLine) / 2 + 2, labelY, heightColor);
    }

    private static void drawBearingRing(PoseStack poseStack, int cx, int cy) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.DEBUG_LINE_STRIP, DefaultVertexFormat.POSITION_COLOR);

        int outerColor = 0x44FFFFFF;
        for (int i = 0; i <= BEARING_RING_SEGMENTS; i++) {
            double angle = 2.0 * Math.PI * i / BEARING_RING_SEGMENTS;
            float x = cx + (float) (Math.cos(angle) * BEARING_RING_RADIUS);
            float y = cy + (float) (Math.sin(angle) * BEARING_RING_RADIUS);
            bufferBuilder.vertex(x, y, 0).color(outerColor).endVertex();
        }

        bufferBuilder.end();
        BufferUploader.end(bufferBuilder);
        RenderSystem.disableBlend();
    }

    private static void drawArrowLine(PoseStack poseStack, float fromX, float fromY, float toX, float toY, String cardinal) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        int lineColor = 0xCCFFFFFF;
        float dx = toX - fromX;
        float dy = toY - fromY;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.5f) {
            bufferBuilder.end();
            BufferUploader.end(bufferBuilder);
            return;
        }
        float nx = -dy / len * 1.5f;
        float ny = dx / len * 1.5f;

        bufferBuilder.vertex(fromX + nx, fromY + ny, 0).color(lineColor).endVertex();
        bufferBuilder.vertex(toX + nx, toY + ny, 0).color(lineColor).endVertex();
        bufferBuilder.vertex(toX - nx, toY - ny, 0).color(lineColor).endVertex();
        bufferBuilder.vertex(fromX - nx, fromY - ny, 0).color(lineColor).endVertex();

        bufferBuilder.end();
        BufferUploader.end(bufferBuilder);

        if (cardinal != null) {
            var font = Minecraft.getInstance().font;
            float labelX = toX;
            float labelY = toY - 3;
            poseStack.pushPose();
            poseStack.translate(labelX, labelY, 0);
            float textScale = 0.6f;
            poseStack.scale(textScale, textScale, 1.0f);
            font.draw(poseStack, cardinal, -font.width(cardinal) / 2.0f, 0, 0x88FFFFFF);
            poseStack.popPose();
        }

        RenderSystem.disableBlend();
    }
}
