package com.nodiumhosting.vaultmapper.mixin;

import com.google.common.collect.Ordering;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nodiumhosting.vaultmapper.map.VaultMap;
import com.nodiumhosting.vaultmapper.util.ColorUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

import static net.minecraft.client.gui.GuiComponent.fill;

@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {
    @Final
    @Shadow
    private static Ordering<PlayerInfo> PLAYER_ORDERING;

    @Final
    @Shadow
    private Minecraft minecraft;

    @Shadow
    private Component header;

    @Shadow
    private Component footer;

    @Shadow
    public Component getNameForDisplay(PlayerInfo pPlayerInfo) {
        return new TextComponent("thing");
    }

    @Inject(method = "render", at = @At("HEAD"))
    public void render(PoseStack pPoseStack, int pWidth, Scoreboard pScoreboard, Objective pObjective, CallbackInfo ci) {
        ClientPacketListener clientpacketlistener = this.minecraft.player.connection;
        List<PlayerInfo> list = PLAYER_ORDERING.sortedCopy(clientpacketlistener.getOnlinePlayers());
        int i = 0;
        int j = 0;

        for (PlayerInfo playerinfo : list) {
            int k = this.minecraft.font.width(this.getNameForDisplay(playerinfo));
            i = Math.max(i, k);
            if (pObjective != null && pObjective.getRenderType() != ObjectiveCriteria.RenderType.HEARTS) {
                k = this.minecraft.font.width(" " + pScoreboard.getOrCreatePlayerScore(playerinfo.getProfile().getName(), pObjective).getScore());
                j = Math.max(j, k);
            }
        }

        list = list.subList(0, Math.min(list.size(), 80));
        int i4 = list.size();
        int j4 = i4;

        int k4;
        for (k4 = 1; j4 > 20; j4 = (i4 + k4 - 1) / k4) {
            ++k4;
        }


        boolean flag = this.minecraft.isLocalServer() || this.minecraft.getConnection().getConnection().isEncrypted();
        int l;
        if (pObjective != null) {
            if (pObjective.getRenderType() == ObjectiveCriteria.RenderType.HEARTS) {
                l = 90;
            } else {
                l = j;
            }
        } else {
            l = 0;
        }

        int i1 = Math.min(k4 * ((flag ? 9 : 0) + i + l + 13), pWidth - 50) / k4;
        int j1 = pWidth / 2 - (i1 * k4 + (k4 - 1) * 5) / 2;
        int k1 = 10;

        for (int i5 = 0; i5 < i4; ++i5) {
            String uuid = list.get(i5).getProfile().getId().toString();
            if (VaultMap.players.containsKey(uuid)) {

                int j5 = i5 / j4;
                int j2 = i5 % j4;
                int k2 = j1 + j5 * i1 + j5 * 5;
                int l2 = k1 + j2 * 9;

                fill(pPoseStack, k2 - 8, l2, k2, l2 + 8, ColorUtil.parseHexColor(VaultMap.players.get(uuid).color));
            }
        }
    }
}
