package com.nodiumhosting.vaultmapper.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class Util {
    public static final Gson GSON = new Gson();
    public static final Gson MAP_GSON = new GsonBuilder()
            .registerTypeAdapter(Boolean.class, new BooleanSerializer())
            .registerTypeAdapter(boolean.class, new BooleanSerializer())
            .create();

    public static String RandomColor() {
        return "#" + RandomHex() + RandomHex() + RandomHex() + RandomHex() + RandomHex() + RandomHex();
    }

    public static String RandomHex() {
        return Integer.toHexString((int)(Math.random()*0xF));
    }
}
