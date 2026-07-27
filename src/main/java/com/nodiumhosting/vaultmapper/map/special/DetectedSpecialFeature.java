package com.nodiumhosting.vaultmapper.map.special;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

public class DetectedSpecialFeature {
    public final String featureId;
    public final String displayText;
    public final String primaryValue;
    public final Integer matchIndex;
    public final CompoundTag rawNbt;
    public BlockPos position;

    public DetectedSpecialFeature(String featureId, String displayText, String primaryValue, Integer matchIndex, CompoundTag rawNbt) {
        this.featureId = featureId;
        this.displayText = displayText;
        this.primaryValue = primaryValue;
        this.matchIndex = matchIndex;
        this.rawNbt = rawNbt;
    }

    @Override
    public String toString() {
        return "DetectedSpecialFeature{" +
                "featureId='" + featureId + '\'' +
                ", displayText='" + displayText + '\'' +
                ", primaryValue='" + primaryValue + '\'' +
                ", matchIndex=" + matchIndex +
                ", position=" + position +
                '}';
    }
}
