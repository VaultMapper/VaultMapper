package com.nodiumhosting.vaultmapper.mixin;

import com.nodiumhosting.vaultmapper.map.RoomData;
import iskallia.vault.init.ModConfigs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ModConfigs.class, remap = false)
public class MixinModConfigs {
    @Inject(method = "registerGen", at = @At("TAIL"))
    private static void captureRooms(CallbackInfo ci){
        RoomData.initRooms();
    }
}
