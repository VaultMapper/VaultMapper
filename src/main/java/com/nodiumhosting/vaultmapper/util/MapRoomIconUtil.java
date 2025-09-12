package com.nodiumhosting.vaultmapper.util;

import iskallia.vault.init.ModConfigs;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public class MapRoomIconUtil {
    public enum BaseIcons {
        ROOM_START("the_vault:textures/gui/map/start.png"),

        ROOM_COMMON("the_vault:textures/gui/map/room.png"),
        ROOM_CHALLENGE("the_vault:textures/gui/map/room_challenge.png"),
        ROOM_OMEGA("the_vault:textures/gui/map/room_omega.png"),

        ROOM_RAW("the_vault:textures/gui/map/raw.png"),
        ROOM_ORE("the_vault:textures/gui/map/ore.png"),
        ROOM_BOSS("the_vault:textures/gui/map/boss.png"),
        ROOM_RAID("the_vault:textures/gui/map/raid.png"),
        ROOM_FARM("the_vault:textures/gui/map/farm.png"),
        ROOM_EMERALD_CAVES("the_vault:textures/gui/map/emerald_caves.png"),
        ROOM_DIAMOND_CAVES("the_vault:textures/gui/map/diamond_caves.png"),
        ROOM_CHROMATIC_CAVES("the_vault:textures/gui/map/chromatic_caves.png"),

        ROOM_MODDED_CAVES("the_vault:textures/gui/map/vm_modded_caves.png"),
        ROOM_WILD_WEST("the_vault:textures/gui/map/vm_wild_west.png"),
        ROOM_CUBE("the_vault:textures/gui/map/vm_cube.png"),
        ROOM_END("the_vault:textures/gui/map/vm_end.png"),
        ROOM_NETHER("the_vault:textures/gui/map/vm_nether.png"),
        ROOM_QUARRY("the_vault:textures/gui/map/vm_quarry.png");

        private final ResourceLocation resourceLocation;
        BaseIcons(String path) {
            this.resourceLocation = new ResourceLocation(path);
        }
        public ResourceLocation getResourceLocation() {
            return resourceLocation;
        }
    }

    public static ResourceLocation getIconForRoom(String roomName) {
        String path = roomName.toLowerCase();
        ResourceLocation icon;


        if (path.contains("start")) {
            icon = BaseIcons.ROOM_START.getResourceLocation();
        } else {
            Optional<ResourceLocation> customIcon = ModConfigs.VAULT_MAP_ICONS.getIconForRoom(new ResourceLocation(path));
            if (customIcon.isPresent()) {
                String iconNamespace = customIcon.get().getNamespace();
                String iconPath = customIcon.get().getPath();
                icon = new ResourceLocation(iconNamespace, "textures/" + iconPath + ".png");
            }
            else if (path.contains("boss")) {
                icon = BaseIcons.ROOM_BOSS.getResourceLocation();
            }
            else if (path.contains("raid")) {
                icon = BaseIcons.ROOM_RAID.getResourceLocation();
            }
            else if (path.contains("farm")) {
                icon = BaseIcons.ROOM_FARM.getResourceLocation();
            }
            else if (path.contains("emerald_cave")) {
                icon = BaseIcons.ROOM_EMERALD_CAVES.getResourceLocation();
            }
            else if (path.contains("diamond_cave")) {
                icon = BaseIcons.ROOM_DIAMOND_CAVES.getResourceLocation();
            }
            else if (path.contains("chromatic_cave")) {
                icon = BaseIcons.ROOM_CHROMATIC_CAVES.getResourceLocation();
            }
            else if (path.contains("modded_cave")) {
                icon = BaseIcons.ROOM_MODDED_CAVES.getResourceLocation();
            }
            else if (path.contains("wild_west")) {
                icon = BaseIcons.ROOM_WILD_WEST.getResourceLocation();
            }
            else if (path.contains("cube")) {
                icon = BaseIcons.ROOM_CUBE.getResourceLocation();
            }
            else if (path.contains("end")) {
                icon = BaseIcons.ROOM_END.getResourceLocation();
            }
            else if (path.contains("nether")) {
                icon = BaseIcons.ROOM_NETHER.getResourceLocation();
            }
            else if (path.contains("quarry")) {
                icon = BaseIcons.ROOM_QUARRY.getResourceLocation();
            }
            else if (path.contains("omega")) {
                icon = BaseIcons.ROOM_OMEGA.getResourceLocation();
            }
            else if (path.contains("challenge")) {
                icon = BaseIcons.ROOM_CHALLENGE.getResourceLocation();
            }
            else if (path.contains("raw")) {
                icon = BaseIcons.ROOM_RAW.getResourceLocation();
            }
            else if (path.contains("/ore")) {
                icon = BaseIcons.ROOM_ORE.getResourceLocation();
            }
            else {
                icon = BaseIcons.ROOM_COMMON.getResourceLocation();
            }
        }

        return icon;
    }
}
