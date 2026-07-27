package com.nodiumhosting.vaultmapper.util;

import com.nodiumhosting.vaultmapper.VaultMapper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

public final class VaultDimensionUtil {
    private VaultDimensionUtil() {
    }

    public static boolean isVaultNamespace(ResourceLocation dimensionLocation) {
        return dimensionLocation != null && "the_vault".equals(dimensionLocation.getNamespace());
    }

    public static boolean isVaultDimension(ResourceLocation dimensionLocation) {
        return isVaultNamespace(dimensionLocation)
                && dimensionLocation.getPath() != null
                && VaultMapper.isVaultDimension(dimensionLocation.getPath());
    }

    public static boolean isInVaultNamespace(Player player) {
        return player != null && isVaultNamespace(player.level.dimension().location());
    }

    public static boolean isInVaultDimension(Player player) {
        return player != null && isVaultDimension(player.level.dimension().location());
    }
}
