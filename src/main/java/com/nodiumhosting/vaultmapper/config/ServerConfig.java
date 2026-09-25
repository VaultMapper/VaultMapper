package com.nodiumhosting.vaultmapper.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class ServerConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<Integer> SYNC_RATE_LIMIT;
    public static final ForgeConfigSpec.ConfigValue<Integer> EMPTY_VAULT_RETENTION_HOURS;

    static {
        BUILDER.push("VaultMapper Server Config");

        SYNC_RATE_LIMIT = BUILDER.comment("The maximum number of player position updates synced per second per vault.\n" +
                "Set to 0 to disable the rate limit.").define("SYNC_RATE_LIMIT", 10);

        EMPTY_VAULT_RETENTION_HOURS = BUILDER.comment("How many hours a vault is kept around (in memory and on disk) after its last player left.\n" +
                "Set to 0 to forget vaults as soon as they are empty.").define("EMPTY_VAULT_RETENTION_HOURS", 72);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }
}
