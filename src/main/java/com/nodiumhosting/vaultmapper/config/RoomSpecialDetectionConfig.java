package com.nodiumhosting.vaultmapper.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Complete room special detection configuration containing all feature definitions.
 */
public class RoomSpecialDetectionConfig {
    public int version = 1;
    public List<RoomSpecialFeatureDefinition> features = new ArrayList<>();

    public RoomSpecialFeatureDefinition getFeatureById(String id) {
        if (id == null || features == null) {
            return null;
        }
        return features.stream()
                .filter(f -> id.equals(f.id))
                .findFirst()
                .orElse(null);
    }

    public List<RoomSpecialFeatureDefinition> getEnabledFeatures() {
        if (features == null) {
            return new ArrayList<>();
        }
        return features;
    }
}
