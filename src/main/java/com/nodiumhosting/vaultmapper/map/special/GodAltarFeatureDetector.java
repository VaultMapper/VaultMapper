package com.nodiumhosting.vaultmapper.map.special;

import com.nodiumhosting.vaultmapper.config.RoomSpecialFeatureDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Detector for god altar features.
 * Extracts god name and challenge title from altar NBT.
 */
public class GodAltarFeatureDetector implements RoomSpecialFeatureDetector {
    public static final String FEATURE_ID = "god_altar";

    @Override
    public String getFeatureId() {
        return FEATURE_ID;
    }

    @Override
    public List<DetectedSpecialFeature> detectFromNbt(RoomSpecialFeatureDefinition definition, BlockState blockState, CompoundTag nbt, Player player) {
        List<DetectedSpecialFeature> results = new ArrayList<>();

        if (nbt == null) {
            return results;
        }

        // Extract god name and challenge title
        String godName = RoomSpecialNbtParser.extractGodName(blockState, nbt);
        String challengeTitle = RoomSpecialNbtParser.extractGodChallengeTitle(nbt);

        if (godName == null || godName.isEmpty()) {
            return results;
        }

        String displayText = godName + ": " + (challengeTitle != null ? challengeTitle : "Unknown Challenge");

        DetectedSpecialFeature feature = new DetectedSpecialFeature(
                FEATURE_ID,
                displayText,
                godName,
                null,  // No match index for god altars
                nbt
        );
        results.add(feature);

        return results;
    }
}
