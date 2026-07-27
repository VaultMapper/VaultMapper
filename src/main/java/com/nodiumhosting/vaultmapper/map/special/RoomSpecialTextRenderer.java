package com.nodiumhosting.vaultmapper.map.special;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;

public final class RoomSpecialTextRenderer {
    public static final int GOD_IDONA_COLOR = 16733525;
    public static final int GOD_TENOS_COLOR = 5636095;
    public static final int GOD_VELARA_COLOR = 5635925;
    public static final int GOD_WENDARR_COLOR = 16755200;
    public static final int PYLON_TIME_COLOR = 0xFF6600FF; // Bright blue for time pylons

    private RoomSpecialTextRenderer() {
    }

    public static void drawSpecialInfoLine(PoseStack poseStack, float centerX, float y, String packedLine, boolean brazierMatchedTarget, float scale) {
        var font = Minecraft.getInstance().font;

        String[] section = packedLine.split("\\|", 3);
        if (section.length < 3) {
            return;
        }

        String kind = section[0];
        String relation = section[1];
        String payload = section[2];

        String arrow = "■";
        int arrowColor = 0xFFFFFF;
        if ("UP".equals(relation)) {
            arrow = "▲";
            arrowColor = 0x55FF55;
        } else if ("DOWN".equals(relation)) {
            arrow = "▼";
            arrowColor = 0xFF5555;
        } else if ("SAME".equals(relation)) {
            arrow = "◆";
            arrowColor = 0xFFDD55;
        }

        int payloadColor = 0xFFFFFF;
        String leftSegment = payload;
        String rightSegment = "";

        if ("BRAZIER".equals(kind) && brazierMatchedTarget) {
            payloadColor = GOD_WENDARR_COLOR;
        }

        if ("CAKE".equals(kind)) {
            payloadColor = 0xFFD166;
        }

        if ("PYLON".equals(kind)) {
            // Check if this is a time pylon (marked with ⏱)
            if (payload.contains("⏱")) {
                payloadColor = PYLON_TIME_COLOR;
            } else {
                payloadColor = 0xA3D8FF;
            }
        }

        if ("GOD".equals(kind)) {
            int colon = payload.indexOf(':');
            if (colon > 0) {
                leftSegment = payload.substring(0, colon);
                rightSegment = payload.substring(colon);
                payloadColor = getGodNameColor(leftSegment);
            }
        }

        int arrowWidth = font.width(arrow + " ");
        int leftWidth = font.width(leftSegment);
        int rightWidth = font.width(rightSegment);
        int totalWidth = arrowWidth + leftWidth + rightWidth;

        float xStart = centerX - (totalWidth * scale) / 2.0f;
        poseStack.pushPose();
        poseStack.translate(xStart, y, 0);
        poseStack.scale(scale, scale, 1.0f);

        font.draw(poseStack, arrow + " ", 0, 0, arrowColor);
        font.draw(poseStack, leftSegment, arrowWidth, 0, payloadColor);
        if (!rightSegment.isEmpty()) {
            font.draw(poseStack, rightSegment, arrowWidth + leftWidth, 0, 0xFFFFFF);
        }

        poseStack.popPose();
    }

    public static int getGodNameColor(String godName) {
        String lower = godName == null ? "" : godName.toLowerCase();
        if (lower.contains("idona")) {
            return GOD_IDONA_COLOR;
        }
        if (lower.contains("tenos")) {
            return GOD_TENOS_COLOR;
        }
        if (lower.contains("velara")) {
            return GOD_VELARA_COLOR;
        }
        if (lower.contains("wendarr")) {
            return GOD_WENDARR_COLOR;
        }
        return 0xFFFFFF;
    }
}
