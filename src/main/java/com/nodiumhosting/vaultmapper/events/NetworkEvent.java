package com.nodiumhosting.vaultmapper.events;

import com.nodiumhosting.vaultmapper.map.VaultMap;
import com.nodiumhosting.vaultmapper.map.VaultMapOverlayRenderer;
import com.nodiumhosting.vaultmapper.map.snapshots.MapCache;
import com.nodiumhosting.vaultmapper.util.VaultDimensionUtil;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber({Dist.CLIENT})
public class NetworkEvent {
    // NEEDS MORE TESTING

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void loginHandler(ClientPlayerNetworkEvent.LoggedInEvent event) {
        if (VaultDimensionUtil.isInVaultDimension(event.getPlayer())) {
            MapCache.readCache();
            VaultMap.startSync(event.getPlayer().getUUID().toString(), event.getPlayer().level.dimension().location().getPath());
            VaultMap.enabled = true;
            VaultMapOverlayRenderer.enabled = true;
        } else {
            VaultMapOverlayRenderer.enabled = false;
            VaultMap.enabled = false;
            VaultMap.resetMap();
        }
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void logoutHandler(ClientPlayerNetworkEvent.LoggedOutEvent event) {
        VaultMapOverlayRenderer.enabled = false;
        VaultMap.enabled = false;
        VaultMap.stopSync();
    }
}
