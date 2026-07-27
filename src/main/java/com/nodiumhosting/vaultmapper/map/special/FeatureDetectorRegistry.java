package com.nodiumhosting.vaultmapper.map.special;

import com.nodiumhosting.vaultmapper.VaultMapper;
import com.nodiumhosting.vaultmapper.config.RoomSpecialFeatureDefinition;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry for room special feature detectors.
 * Maps feature IDs to their corresponding detector implementations.
 */
public class FeatureDetectorRegistry {
    private static final Map<String, RoomSpecialFeatureDetector> detectors = new HashMap<>();

    static {
        // Register built-in detectors
        register(new BrazierFeatureDetector());
        register(new GodAltarFeatureDetector());
        register(new CakeFeatureDetector());
        register(new PylonFeatureDetector());
    }

    public static void register(RoomSpecialFeatureDetector detector) {
        if (detector == null || detector.getFeatureId() == null) {
            VaultMapper.LOGGER.warn("Attempted to register null or invalid detector");
            return;
        }
        detectors.put(detector.getFeatureId(), detector);
        VaultMapper.LOGGER.debug("Registered feature detector: {}", detector.getFeatureId());
    }

    public static RoomSpecialFeatureDetector getDetector(String featureId) {
        return detectors.get(featureId);
    }

    public static RoomSpecialFeatureDetector getDetectorForDefinition(RoomSpecialFeatureDefinition definition) {
        if (definition == null || definition.id == null) {
            return null;
        }
        return getDetector(definition.id);
    }

    public static boolean hasDetector(String featureId) {
        return detectors.containsKey(featureId);
    }
}
