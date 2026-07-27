package com.nodiumhosting.vaultmapper.map.special;

import com.nodiumhosting.vaultmapper.config.RoomSpecialFeatureDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Detector for pylon room specials.
 * Pylons are identified by their block state and derive their display text from the tile entity config.
 */
public class PylonFeatureDetector implements RoomSpecialFeatureDetector {
    public static final String FEATURE_ID = "pylon";
    private static final String DEFAULT_BLOCK_ID = "the_vault:pylon";

    @Override
    public String getFeatureId() {
        return FEATURE_ID;
    }

    @Override
    public List<DetectedSpecialFeature> detectFromNbt(RoomSpecialFeatureDefinition definition, BlockState blockState, CompoundTag nbt, Player player) {
        List<DetectedSpecialFeature> results = new ArrayList<>();
        if (blockState == null || blockState.getBlock() == null || blockState.getBlock().getRegistryName() == null) {
            return results;
        }

        String blockId = blockState.getBlock().getRegistryName().toString();
        String expectedBlockId = definition != null && definition.blockId != null && !definition.blockId.isEmpty()
                ? definition.blockId
                : DEFAULT_BLOCK_ID;
        if (!expectedBlockId.equals(blockId)) {
            return results;
        }

        String displayText = RoomSpecialNbtParser.extractPylonDescription(nbt);
        if (displayText == null || displayText.isEmpty()) {
            displayText = definition != null && definition.displayName != null && !definition.displayName.isEmpty()
                    ? definition.displayName
                    : "Pylon";
        }

        results.add(new DetectedSpecialFeature(
                FEATURE_ID,
                displayText,
                displayText,
                null,
                nbt
        ));
        return results;
    }
}
