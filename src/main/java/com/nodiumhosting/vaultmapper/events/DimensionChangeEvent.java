package com.nodiumhosting.vaultmapper.events;

import com.nodiumhosting.vaultmapper.map.VaultMap;
import com.nodiumhosting.vaultmapper.map.VaultMapOverlayRenderer;
import com.nodiumhosting.vaultmapper.map.snapshots.MapCache;
import com.nodiumhosting.vaultmapper.map.snapshots.MapSnapshot;
import com.nodiumhosting.vaultmapper.util.VaultDimensionUtil;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber({Dist.CLIENT})
public class DimensionChangeEvent {
    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onDimChange(ClientPlayerNetworkEvent.RespawnEvent event) {
        MapSnapshot.lastSnapshotCache = MapSnapshot.takeSnapshot();
        VaultMap.resetMap();

        if (VaultDimensionUtil.isVaultDimension(event.getNewPlayer().level.dimension().location())) {
            VaultMap.startSync(event.getNewPlayer().getUUID().toString(), event.getNewPlayer().level.dimension().location().getPath());
            VaultMap.enabled = true;
            VaultMapOverlayRenderer.enabled = true;
        } else {
            VaultMap.enabled = false;
            VaultMapOverlayRenderer.enabled = false;
            VaultMap.stopSync();
            MapCache.deleteCache();
        }
    }
}
