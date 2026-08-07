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

// Saves the server address at connect time since mismatch kicks happen before LocalPlayer exists and Minecraft.getCurrentServer() is null by then.
@Mixin(ConnectScreen.class)
abstract class ConnectScreenMixin {

    @Inject(method = "startConnecting", at = @At("HEAD"))
    private static void bamfmenu$captureTarget(Screen parent, Minecraft minecraft, ServerAddress address,
                                               ServerData serverData, boolean isQuickPlay,
                                               TransferState transferState, CallbackInfo ci) {
        KickWatch.connectingTo(address.getHost(), address.getPort());
    }
}
