package com.nodiumhosting.vaultmapper;

import com.mojang.logging.LogUtils;
import com.nodiumhosting.vaultmapper.commands.VaultMapperCommand;
import com.nodiumhosting.vaultmapper.config.ClientConfig;
import com.nodiumhosting.vaultmapper.events.KeybindEvents;
import com.nodiumhosting.vaultmapper.map.VaultMapOverlayRenderer;
import com.nodiumhosting.vaultmapper.util.UpdateChecker;
import com.nodiumhosting.vaultmapper.util.Util;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The value here should match an entry in the META-INF/mods.toml file
@Mod("vaultmapper")
public class VaultMapper {
    public static final String MODID = "vaultmapper";

    public static final Logger LOGGER = LogUtils.getLogger();

    public static Pattern vault_regex = Pattern.compile("vault_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    public VaultMapper() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onClientSetup);

        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC, MODID + "-client.toml");
    }

    public static String getVersion() {
        String version = ModList.get()
                .getModContainerById(VaultMapper.MODID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("Unknown");
        if (version.equals("0.0NONE")) {
            version = "DEV";
        }
        return version;
    }

    public static boolean isVaultDimension(String vault_string) {
        Matcher matcher = vault_regex.matcher(vault_string);
        return matcher.find();
    }

    private void setup(final FMLCommonSetupEvent event) {
        LOGGER.info("hallo! (VaultMapper)");

    }

    @SubscribeEvent
    public void onClientSetup(FMLClientSetupEvent event) {
        if (ClientConfig.SYNC_COLOR.get().equals("random")) {
            ClientConfig.SYNC_COLOR.set(Util.RandomColor());
        }

        UpdateChecker.checkForUpdates();
        KeybindEvents.registerKeyBinds();

        VaultMapOverlayRenderer.prep();
    }

    @Mod.EventBusSubscriber(modid = VaultMapper.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static class ModEventListener {
        @SubscribeEvent
        public static void registerClientCommands(RegisterClientCommandsEvent event) {
            VaultMapperCommand.register(event.getDispatcher());
            LOGGER.info("registered client commands");
        }
    }
}
