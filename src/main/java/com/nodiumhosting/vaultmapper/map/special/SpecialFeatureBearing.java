package com.nodiumhosting.vaultmapper.map.special;

import net.minecraft.core.BlockPos;

public class SpecialFeatureBearing {
    public final double yawDegrees;
    public final double pitchDegrees;
    public final double distance;
    public final double yLevelDiff;
    public final String cardinalDirection;
    public final boolean isBelow;
    public final boolean isAbove;
    public final boolean isSameLevel;
    public final BlockPos targetPos;

    private static final String[] CARDINALS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

    public SpecialFeatureBearing(double yawDegrees, double pitchDegrees, double distance,
                                  double yLevelDiff, BlockPos targetPos) {
        this.yawDegrees = yawDegrees;
        this.pitchDegrees = pitchDegrees;
        this.distance = distance;
        this.yLevelDiff = yLevelDiff;
        this.targetPos = targetPos;

        double normalized = ((yawDegrees % 360) + 360) % 360;
        int index = (int) Math.round(normalized / 45.0) % 8;
        this.cardinalDirection = CARDINALS[index];

        double threshold = 2.5;
        this.isAbove = yLevelDiff > threshold;
        this.isBelow = yLevelDiff < -threshold;
        this.isSameLevel = !isAbove && !isBelow;
    }
}
