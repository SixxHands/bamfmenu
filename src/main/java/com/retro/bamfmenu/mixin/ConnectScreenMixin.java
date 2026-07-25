package com.retro.bamfmenu.mixin;

import com.retro.bamfmenu.client.KickWatch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the server address at CONNECT time for the blue screen's pack-server gate.
 * The gap. Mismatch kicks land in the CONFIGURATION phase, before LocalPlayer exists, so
 * Minecraft.getCurrentServer() is null on the disconnect screen -- there is nothing to read at kick
 * time. The guard. startConnecting is the one choke point every multiplayer connection goes
 * through (server list, direct connect, quickplay, transfers); we stash the address on the way in.
 */
@Mixin(ConnectScreen.class)
abstract class ConnectScreenMixin {

    @Inject(method = "startConnecting", at = @At("HEAD"))
    private static void bamfmenu$captureTarget(Screen parent, Minecraft minecraft, ServerAddress address,
                                               ServerData serverData, boolean isQuickPlay,
                                               TransferState transferState, CallbackInfo ci) {
        KickWatch.connectingTo(address.getHost(), address.getPort());
    }
}
