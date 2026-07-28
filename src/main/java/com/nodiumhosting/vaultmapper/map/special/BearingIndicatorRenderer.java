package com.nodiumhosting.vaultmapper.map.special;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.nodiumhosting.vaultmapper.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class BearingIndicatorRenderer {
    private static final float MAX_MARKER_DIST = 80.0f;
    private static final float VIEW_CONE_DOT = (float) Math.cos(Math.toRadians(75));

    private BearingIndicatorRenderer() {
    }

    public static void render(PoseStack poseStack, String label, BlockPos targetPos, int color) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!ClientConfig.SHOW_FEATURE_MARKERS.get()) return;
        if (targetPos == null) return;

        Vec3 target = new Vec3(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);
        Vec3 camPos = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 toTarget = target.subtract(camPos);
        float dist = (float) toTarget.length();

        if (dist > MAX_MARKER_DIST || dist < 0.5f) return;

        Vec3 lookVec = new Vec3(mc.gameRenderer.getMainCamera().getLookVector());
        Vec3 normToTarget = toTarget.normalize();

        float dotProduct = (float) lookVec.dot(normToTarget);
        if (dotProduct < VIEW_CONE_DOT) return;

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        float fov = (float) mc.options.fov;

        if (fov <= 0) return;

        double halfH = Math.tan(Math.toRadians(fov / 2.0));
        double halfW = halfH * ((double) sw / sh);

        Vec3 forward = lookVec;
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 right = forward.cross(up).normalize();
        Vec3 cameraUp = right.cross(forward).normalize();

        double dx = toTarget.dot(right);
        double dy = toTarget.dot(cameraUp);
        double dz = toTarget.dot(forward);

        int screenX = (int) (sw / 2.0 + (dx / (halfW * dz)) * (sw / 2.0));
        int screenY = (int) (sh / 2.0 - (dy / (halfH * dz)) * (sh / 2.0));

        if (screenX < -50 || screenX > sw + 50 || screenY < -50 || screenY > sh + 50) return;

        float yDiff = (float) (target.y - mc.player.getY());
        int markerColor = (color & 0x00FFFFFF) | 0xCC000000;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        float dotRadius = Math.max(2.0f, Math.min(6.0f, 8.0f - dist * 0.06f));
        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bufferBuilder.vertex(screenX - dotRadius, screenY + dotRadius, 0).color(markerColor).endVertex();
        bufferBuilder.vertex(screenX + dotRadius, screenY + dotRadius, 0).color(markerColor).endVertex();
        bufferBuilder.vertex(screenX + dotRadius, screenY - dotRadius, 0).color(markerColor).endVertex();
        bufferBuilder.vertex(screenX - dotRadius, screenY - dotRadius, 0).color(markerColor).endVertex();
        bufferBuilder.end();
        BufferUploader.end(bufferBuilder);

        RenderSystem.disableBlend();

        var font = mc.font;
        String distStr = String.format("%.0fm", dist);
        int textColor = 0xFFFFFFFF;
        int textX = screenX + (int) dotRadius + 3;
        int textY = screenY - font.lineHeight / 2;
        font.draw(poseStack, distStr, textX, textY, textColor);

        String heightChar;
        int heightColor;
        if (yDiff > 1.5) { heightChar = "\u25B2"; heightColor = 0xFF55FF55; }
        else if (yDiff < -1.5) { heightChar = "\u25BC"; heightColor = 0xFFFF5555; }
        else { heightChar = "\u25C6"; heightColor = 0xFFFFDD55; }
        font.draw(poseStack, heightChar, textX + font.width(distStr) + 2, textY, heightColor);

        font.draw(poseStack, label, screenX - font.width(label) / 2, screenY - (int) dotRadius - font.lineHeight - 2, textColor);
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
