package com.nodiumhosting.vaultmapper.events;

import com.nodiumhosting.vaultmapper.VaultMapper;
import com.nodiumhosting.vaultmapper.config.ClientConfig;
import com.nodiumhosting.vaultmapper.gui.screen.VaultMapperConfigScreen;
import com.nodiumhosting.vaultmapper.map.VaultMap;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ClientRegistry;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = VaultMapper.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class KeybindEvents {

    public static KeyMapping markKey;
    public static KeyMapping openConfigKey;
    public static KeyMapping syncReconnectKey;
    public static KeyMapping toggleMapKey;
    public static KeyMapping quickToggleMapVisibilityKey;
    public static KeyMapping toggleViewerCode;

    public static void registerKeyBinds() {
        markKey = registerKeyMapping("mark", GLFW.GLFW_KEY_MINUS);
        openConfigKey = registerKeyMapping("openconfig", GLFW.GLFW_KEY_F7);
        syncReconnectKey = registerKeyMapping("reconnectsync", GLFW.GLFW_KEY_UNKNOWN);
        toggleMapKey = registerKeyMapping("togglemap", GLFW.GLFW_KEY_EQUAL);
        quickToggleMapVisibilityKey = registerKeyMapping("quicktogglemap", GLFW.GLFW_KEY_H);
        toggleViewerCode = registerKeyMapping("toggleviewercode", GLFW.GLFW_KEY_UNKNOWN);
    }

    // Helper method to register KeyMappings
    private static KeyMapping registerKeyMapping(String name, int keyCode) {
        KeyMapping key = new KeyMapping("key." + VaultMapper.MODID + "." + name, keyCode, "key.categories." + VaultMapper.MODID);
        ClientRegistry.registerKeyBinding(key);
        return key;
    }

    /**
     * Event that is ran everytime a key is inputted
     */
    @SubscribeEvent
    public static void on(InputEvent.KeyInputEvent event) {
        // Mark current cell
        if(markKey.consumeClick()) {
            VaultMap.markCurrentCell();
        }

        // Set players screen to the config screen
        if(openConfigKey.consumeClick()) {
            Minecraft.getInstance().setScreen(new VaultMapperConfigScreen());
        }

        // Reconnect sync
        if(syncReconnectKey.consumeClick()) {
            if (!VaultMap.enabled) return;
            Player player = Minecraft.getInstance().player;
            if (player == null) return;
            VaultMap.stopSync();
            VaultMap.startSync(player.getUUID().toString(), player.level.dimension().location().getPath());
            player.sendMessage(new TextComponent("VMSync Reconnected"), player.getUUID());
        }

        // Toggle rendering of the VaultMap
        if(toggleMapKey.consumeClick()) {
            VaultMap.toggleRendering();
        }

        if(quickToggleMapVisibilityKey.consumeClick()) {
            VaultMap.toggleQuickMapVisibility();
        }

        // Toggle rendering of the viewer code
        if(toggleViewerCode.consumeClick()) {
            ClientConfig.SHOW_VIEWER_CODE.set(!ClientConfig.SHOW_VIEWER_CODE.get());
        }
    }
}
