package com.nodiumhosting.vaultmapper.map.special;

import com.nodiumhosting.vaultmapper.config.RoomSpecialFeatureDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/**
 * Interface for detecting and parsing a specific room special feature.
 * Implementations handle extraction of meaningful data from block entity NBT.
 */
public interface RoomSpecialFeatureDetector {
    /**
     * Get the feature ID this detector handles
     */
    String getFeatureId();

    /**
     * Detect and parse feature instances from a block entity NBT.
     *
     * @param definition The feature definition from config
     * @param blockState The block state at detection site
     * @param nbt The block entity NBT data
     * @param player The player (for world context)
     * @return List of detected features, or empty list if nothing found
     */
    List<DetectedSpecialFeature> detectFromNbt(RoomSpecialFeatureDefinition definition, BlockState blockState, CompoundTag nbt, Player player);

    /**
     * Check if this detector can handle the given feature definition
     */
    default boolean canHandle(RoomSpecialFeatureDefinition definition) {
        return definition != null && getFeatureId().equals(definition.id);
    }
}
