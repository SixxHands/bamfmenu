package com.retro.bamfmenu.mixin;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/* Owns the OS window title (the taskbar hover / alt-tab name) for the game window: vanilla + NeoForge
   set it to "Minecraft 1.21.1 / NeoForge"; we replace whatever is being set with the app's branded name.
   Forcing it at every setTitle keeps it stable even if MC re-titles later. */
@Mixin(Window.class)
public class WindowTitleMixin {

    /* The branded window / taskbar name shown in place of the vanilla title. */
    private static final String APP_NAME = "BAMF :: MINECRAFT";

    @ModifyVariable(method = "setTitle(Ljava/lang/String;)V", at = @At("HEAD"), argsOnly = true)
    private String bamfmenu$appName(String original) {
        return APP_NAME;
    }
}
