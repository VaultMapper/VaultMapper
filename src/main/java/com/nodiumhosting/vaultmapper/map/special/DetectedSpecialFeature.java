package com.nodiumhosting.vaultmapper.map.special;

import com.nodiumhosting.vaultmapper.config.RoomSpecialFeatureDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Represents a detected special feature instance in a room.
 */
public class DetectedSpecialFeature {
    public final String featureId;
    public final String displayText;
    public final String primaryValue;  // e.g., "Abundant Souls + Bronze Nuke" for brazier
    public final Integer matchIndex;   // For matching against targets (e.g., brazier target index)
    public final CompoundTag rawNbt;

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
                '}';
    }
}
