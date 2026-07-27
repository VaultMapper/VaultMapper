package com.nodiumhosting.vaultmapper.map.special;

import com.nodiumhosting.vaultmapper.config.RoomSpecialFeatureDefinition;
import com.nodiumhosting.vaultmapper.map.special.RoomSpecialNbtParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Detector for brazier/monolith features.
 * Parses modifier tags and matches against configured target modifier sets.
 */
public class BrazierFeatureDetector implements RoomSpecialFeatureDetector {
    public static final String FEATURE_ID = "brazier";

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

        // Parse modifiers from the monolith NBT
        String modifiersText = RoomSpecialNbtParser.parseBrazierModifiers(nbt);
        if (modifiersText == null || modifiersText.isEmpty()) {
            return results;
        }

        // Find matching target index if matching strategy is configured
        Integer matchIndex = null;
        if ("exact_or_subset".equals(definition.matchingStrategy)) {
            CompoundTag modifiersTag = nbt.getCompound("Modifiers");
            matchIndex = RoomSpecialNbtParser.findMatchingBrazierTargetIndex(modifiersTag);
        }

        DetectedSpecialFeature feature = new DetectedSpecialFeature(
                FEATURE_ID,
                modifiersText,
                modifiersText,
                matchIndex,
                nbt
        );
        results.add(feature);

        return results;
    }
}
