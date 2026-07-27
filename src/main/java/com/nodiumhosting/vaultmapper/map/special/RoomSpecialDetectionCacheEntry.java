package com.nodiumhosting.vaultmapper.map.special;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class RoomSpecialDetectionCacheEntry {
    public final String brazierModifiers;
    public final BlockPos brazierPosition;
    public final Integer brazierMatchIndex;
    public final String cakeText;
    public final BlockPos cakePosition;
    public final ArrayList<RoomSpecialPoint> godAltars;
    public final ArrayList<RoomSpecialPoint> pylons;

    public RoomSpecialDetectionCacheEntry(String brazierModifiers, BlockPos brazierPosition, Integer brazierMatchIndex, String cakeText, BlockPos cakePosition, List<RoomSpecialPoint> godAltars, List<RoomSpecialPoint> pylons) {
        this.brazierModifiers = brazierModifiers;
        this.brazierPosition = brazierPosition;
        this.brazierMatchIndex = brazierMatchIndex;
        this.cakeText = cakeText;
        this.cakePosition = cakePosition;
        this.godAltars = new ArrayList<>(godAltars);
        this.pylons = new ArrayList<>(pylons);
    }
}
