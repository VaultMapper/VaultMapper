package com.nodiumhosting.vaultmapper.map.special;

public class RoomSpecialScanProgressEntry {
    public final int minY;
    public final int maxY;
    public final int cursor;
    public final boolean scanGodEnabled;
    public final boolean scanBrazierEnabled;
    public final boolean scanPylonEnabled;

    public RoomSpecialScanProgressEntry(int minY, int maxY, int cursor, boolean scanGodEnabled, boolean scanBrazierEnabled, boolean scanPylonEnabled) {
        this.minY = minY;
        this.maxY = maxY;
        this.cursor = cursor;
        this.scanGodEnabled = scanGodEnabled;
        this.scanBrazierEnabled = scanBrazierEnabled;
        this.scanPylonEnabled = scanPylonEnabled;
    }
}
