package com.nodiumhosting.vaultmapper.map.special;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.nodiumhosting.vaultmapper.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;

public final class BearingIndicatorRenderer {
    private static final int WAYPOINT_ARROW_SIZE = 8;
    private static final int WAYPOINT_OFFSET = 10;
    private static final int WAYPOINT_STACK_GAP = 14;

    private BearingIndicatorRenderer() {
    }

    public static void render(PoseStack poseStack, String label, BlockPos targetPos) {
        if (!ClientConfig.SHOW_SPECIAL_TEXT.get()) return;
        SpecialFeatureBearing bearing = SpecialFeatureBearingCalculator.calculate(targetPos);
        if (bearing == null) return;

        Minecraft mc = Minecraft.getInstance();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        double angleRad = Math.toRadians(bearing.yawDegrees);
        double cos = Math.cos(angleRad);
        double sin = Math.sin(angleRad);

        int edgeX, edgeY;
        boolean onTopOrBottom;

        double slope = (sin != 0) ? cos / sin : Double.MAX_VALUE;

        double hDistToTop = (sin > 0) ? (sh / 2.0) / sin : Double.MAX_VALUE;
        double hDistToBottom = (sin < 0) ? (-sh / 2.0) / sin : Double.MAX_VALUE;
        double vDistToRight = (cos > 0) ? (sw / 2.0) / cos : Double.MAX_VALUE;
        double vDistToLeft = (cos < 0) ? (-sw / 2.0) / cos : Double.MAX_VALUE;

        double minDist = Math.min(Math.min(hDistToTop, hDistToBottom), Math.min(vDistToRight, vDistToLeft));

        if (minDist == hDistToTop) {
            edgeX = (int) (sw / 2.0 + cos * hDistToTop);
            edgeY = WAYPOINT_OFFSET;
            onTopOrBottom = true;
        } else if (minDist == hDistToBottom) {
            edgeX = (int) (sw / 2.0 + cos * hDistToBottom);
            edgeY = sh - WAYPOINT_OFFSET;
            onTopOrBottom = true;
        } else if (minDist == vDistToRight) {
            edgeX = sw - WAYPOINT_OFFSET;
            edgeY = (int) (sh / 2.0 + sin * vDistToRight);
            onTopOrBottom = false;
        } else {
            edgeX = WAYPOINT_OFFSET;
            edgeY = (int) (sh / 2.0 + sin * vDistToLeft);
            onTopOrBottom = false;
        }

        edgeX = Math.max(WAYPOINT_OFFSET, Math.min(sw - WAYPOINT_OFFSET, edgeX));
        edgeY = Math.max(WAYPOINT_OFFSET, Math.min(sh - WAYPOINT_OFFSET, edgeY));

        drawWaypointArrow(poseStack, edgeX, edgeY, angleRad, onTopOrBottom);

        int distColor = 0xFFFFFFFF;
        String distText = String.format("%.0fm", bearing.distance);
        var font = mc.font;

        int textX = edgeX;
        int textY = edgeY + (onTopOrBottom ? 10 : -10);
        if (!onTopOrBottom) {
            textY = edgeY + (sin > 0 ? 10 : -font.lineHeight - 4);
        }

        GuiComponent.drawCenteredString(poseStack, font, distText, textX, textY, distColor);

        String heightChar = bearing.isAbove ? "\u25B2" : bearing.isBelow ? "\u25BC" : "\u25C6";
        int heightColor = bearing.isAbove ? 0xFF55FF55 : bearing.isBelow ? 0xFFFF5555 : 0xFFFFDD55;
        font.draw(poseStack, heightChar, textX + font.width(distText) / 2 + 2, textY, heightColor);
    }

    private static void drawWaypointArrow(PoseStack poseStack, int cx, int cy, double angleRad, boolean vertical) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        int color = 0xCCFFFFFF;

        double tipX, tipY;
        double leftX, leftY;
        double rightX, rightY;

        if (vertical) {
            if (angleRad > -Math.PI / 2 && angleRad < Math.PI / 2) {
                tipX = cx; tipY = cy - WAYPOINT_ARROW_SIZE;
                leftX = cx - WAYPOINT_ARROW_SIZE; leftY = cy;
                rightX = cx + WAYPOINT_ARROW_SIZE; rightY = cy;
            } else {
                tipX = cx; tipY = cy + WAYPOINT_ARROW_SIZE;
                leftX = cx - WAYPOINT_ARROW_SIZE; leftY = cy;
                rightX = cx + WAYPOINT_ARROW_SIZE; rightY = cy;
            }
        } else {
            if (angleRad > 0 && angleRad < Math.PI) {
                tipX = cx + WAYPOINT_ARROW_SIZE; tipY = cy;
                leftX = cx; leftY = cy - WAYPOINT_ARROW_SIZE;
                rightX = cx; rightY = cy + WAYPOINT_ARROW_SIZE;
            } else {
                tipX = cx - WAYPOINT_ARROW_SIZE; tipY = cy;
                leftX = cx; leftY = cy - WAYPOINT_ARROW_SIZE;
                rightX = cx; rightY = cy + WAYPOINT_ARROW_SIZE;
            }
        }

        bufferBuilder.vertex((float) tipX, (float) tipY, 0).color(color).endVertex();
        bufferBuilder.vertex((float) rightX, (float) rightY, 0).color(color).endVertex();
        bufferBuilder.vertex((float) leftX, (float) leftY, 0).color(color).endVertex();

        float stemLen = 3;
        double stemEndX = cx + (cx - tipX) / WAYPOINT_ARROW_SIZE * stemLen;
        double stemEndY = cy + (cy - tipY) / WAYPOINT_ARROW_SIZE * stemLen;
        float sw = 1.5f;

        bufferBuilder.vertex((float) stemEndX - sw, (float) stemEndY, 0).color(color).endVertex();
        bufferBuilder.vertex((float) stemEndX + sw, (float) stemEndY, 0).color(color).endVertex();
        bufferBuilder.vertex(cx - sw, cy, 0).color(color).endVertex();
        bufferBuilder.vertex(cx + sw, cy, 0).color(color).endVertex();

        bufferBuilder.end();
        BufferUploader.end(bufferBuilder);

        RenderSystem.disableBlend();
    }
}
