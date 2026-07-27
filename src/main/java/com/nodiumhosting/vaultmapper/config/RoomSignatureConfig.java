package com.nodiumhosting.vaultmapper.config;

import java.util.ArrayList;
import java.util.List;

public class RoomSignatureConfig {
    public int version = 1;
    public String generatedFrom = "vault_rooms.json";
    public List<RoomSignatureEntry> rooms = new ArrayList<>();

    public static class RoomSignatureEntry {
        public String key;
        public String roomType;
        public String rotationMode;
        public String signatureMatchMode;
        public List<String> variants = new ArrayList<>();
        public List<BlockSignature> signatures = new ArrayList<>();
    }

    public static class BlockSignature {
        public String blockId;
        public List<BlockCoordinate> possiblePositions = new ArrayList<>();
    }

    public static class BlockCoordinate {
        public int x;
        public int y;
        public int z;

        public BlockCoordinate() {
        }

        public BlockCoordinate(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
