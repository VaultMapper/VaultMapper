package com.nodiumhosting.vaultmapper.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Defines a room special feature (brazier, god altar, etc.) that can be detected via scanning.
 * This configuration drives what the scanner looks for and how to parse results.
 */
public class RoomSpecialFeatureDefinition {
    public String id;
    public String blockId;
    public String displayName;
    public String matchingStrategy;  // "exact_or_subset", "none", "exact", "subset"
    public ParsingRules parsingRules;
    public CachingConfig caching;
    public int maxPerRoom;  // Maximum instances per room (0 = unlimited)
    public int minScanYRange;  // Additional vertical scan range (0 = use default)

    public static class ParsingRules {
        public String type;  // "brazier_modifiers", "god_altar", "custom", etc.
        public String nbtPath;  // for modifiers/simple values
        public String godName;  // extraction path for god altar name
        public String challengeTitle;  // extraction path for god altar title
        public Map<String, String> extractionPaths;  // generic extraction paths
        public List<String> modifierKeys;  // keys to look for in NBT
    }

    public static class CachingConfig {
        public boolean enabled = true;
        public long ttl = -1;  // -1 = no expiration
    }

    public RoomSpecialFeatureDefinition() {
        this.maxPerRoom = 0;
        this.minScanYRange = 0;
        this.caching = new CachingConfig();
        this.parsingRules = new ParsingRules();
    }

    @Override
    public String toString() {
        return "RoomSpecialFeatureDefinition{" +
                "id='" + id + '\'' +
                ", blockId='" + blockId + '\'' +
                ", displayName='" + displayName + '\'' +
                ", matchingStrategy='" + matchingStrategy + '\'' +
                ", type=" + (parsingRules != null ? parsingRules.type : "null") +
                '}';
    }
}
