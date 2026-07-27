package com.nodiumhosting.vaultmapper.map.special;

import net.minecraft.core.BlockPos;

public class RoomSpecialPoint {
    public final String text;
    public final BlockPos position;
    public final String type;

    public RoomSpecialPoint(String text, BlockPos position) {
        this(text, position, null);
    }

    public RoomSpecialPoint(String text, BlockPos position, String type) {
        this.text = text;
        this.position = position;
        this.type = type;
    }
}
