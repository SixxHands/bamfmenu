package com.retro.bamfmenu.mixin;

import com.retro.bamfmenu.client.BamfSplashes;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.client.resources.SplashManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/* Makes the title screen pull its splash from our mod-owned list (deterministic, not additive). When
   we have lines, we short-circuit getSplash() with a random pick wrapped in a SplashRenderer; the
   per-line color is applied later by SplashRendererMixin keyed on this exact text. If our list is
   empty we do nothing and vanilla's own splash logic runs. */
@Mixin(SplashManager.class)
public class SplashManagerMixin {

    @Inject(method = "getSplash", at = @At("HEAD"), cancellable = true)
    private void bamfmenu$ourSplash(CallbackInfoReturnable<SplashRenderer> cir) {
        String splash = BamfSplashes.pickRandom();
        if (splash != null) {
            cir.setReturnValue(new SplashRenderer(splash));
        }
    }
}
