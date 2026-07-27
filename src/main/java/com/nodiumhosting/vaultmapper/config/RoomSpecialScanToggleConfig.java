package com.nodiumhosting.vaultmapper.config;

import java.util.LinkedHashMap;
import java.util.Map;

public class RoomSpecialScanToggleConfig {
    public int version = 1;
    public Map<String, Boolean> featureToggles = new LinkedHashMap<>();
}
