package com.nodiumhosting.vaultmapper.map.special;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

public final class SpecialFeatureBearingCalculator {
    private SpecialFeatureBearingCalculator() {
    }

    public static SpecialFeatureBearing calculate(BlockPos targetPos) {
        Player player = Minecraft.getInstance().player;
        if (player == null || targetPos == null) {
            return null;
        }

        double dx = targetPos.getX() + 0.5 - player.getX();
        double dy = targetPos.getY() + 0.5 - player.getY();
        double dz = targetPos.getZ() + 0.5 - player.getZ();

        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        double totalDist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = -Math.toDegrees(Math.atan2(dy, horizontalDist));

        return new SpecialFeatureBearing(yaw, pitch, totalDist, dy, targetPos);
    }
}
