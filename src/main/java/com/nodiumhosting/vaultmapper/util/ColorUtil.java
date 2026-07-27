package com.nodiumhosting.vaultmapper.util;

public final class ColorUtil {
    private ColorUtil() {
    }

    public static int parseHexColor(String hexColor) {
        try {
            String value = hexColor;
            if (value.startsWith("#")) {
                value = value.substring(1);
            }

            if (value.length() == 6) {
                value = "FF" + value;
            }

            return (int) Long.parseLong(value, 16);
        } catch (Exception ignored) {
            return 0xFFFFFFFF;
        }
    }
}
