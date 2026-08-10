package com.nodiumhosting.vaultmapper.mixin;

import com.nodiumhosting.vaultmapper.util.MapRoomIconUtil;
import iskallia.vault.config.VaultMapRoomIconsConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VaultMapRoomIconsConfig.class, remap = false)
public class MixinVaultMapRoomIconsConfig {
    // idk which is right, let's do both
    @Inject(method = "clearCache", at = @At(value = "HEAD"))
    private static void nukeVMCacheHead(CallbackInfo ci){
        MapRoomIconUtil.roomIconMap.clear();
    }

    @Inject(method = "clearCache", at = @At(value = "TAIL"))
    private static void nukeVMCacheTail(CallbackInfo ci){
        MapRoomIconUtil.roomIconMap.clear();
    }

}
