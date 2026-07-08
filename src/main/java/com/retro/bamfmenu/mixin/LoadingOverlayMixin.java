package com.retro.bamfmenu.mixin;

import com.retro.bamfmenu.client.Win95LoadingScreen;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.function.Consumer;

/* Later reloads (F3+T, resource-pack change, world join) use the VANILLA LoadingOverlay, not
   NeoForge's startup subclass, so we terminal-skin this one too. Same idea: cancel vanilla's
   Mojang-logo render and draw the terminal, replicating vanilla's exact fade-in/out + finish
   lifecycle (including the fadeIn guard) so loading always completes into the game. */
@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayMixin {

    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private ReloadInstance reload;
    @Shadow @Final private Consumer<Optional<Throwable>> onFinish;
    @Shadow @Final private boolean fadeIn;
    @Shadow private float currentProgress;
    @Shadow private long fadeOutStart;
    @Shadow private long fadeInStart;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void bamfmenu$terminal(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        long k = Util.getMillis();
        if (this.fadeIn && this.fadeInStart == -1L) {
            this.fadeInStart = k;
        }
        float f = this.fadeOutStart > -1L ? (float) (k - this.fadeOutStart) / 1000.0F : -1.0F;
        float f1 = this.fadeInStart > -1L ? (float) (k - this.fadeInStart) / 500.0F : -1.0F;
        this.currentProgress = Mth.clamp(this.currentProgress * 0.95F + this.reload.getActualProgress() * 0.05F, 0.0F, 1.0F);

        float fade;
        if (f >= 0.0F) {
            /* Ease-out dissolve over ~2s: opacity drops FAST at the start (so it begins blending into
               the menu immediately -- no late feel) then eases to nothing, staying visible the whole
               span so it reads as a crossfade, not a cut. The 2s span is what made it look "together";
               the squared curve is what removes the slow-start "too late" feel. */
            float t = Mth.clamp(f / 2.0F, 0.0F, 1.0F);
            fade = (1.0F - t) * (1.0F - t);
        } else if (this.fadeIn) {
            fade = Mth.clamp(f1, 0.0F, 1.0F);
        } else {
            fade = 1.0F;
        }

        /* Render the menu (title screen: background + logo + buttons) behind for the ENTIRE fade-out, so
           as the loading screen fades out the full menu fades in at the same time -- one unified
           crossfade, everything together. */
        if (f >= 0.0F && this.minecraft.screen != null) {
            this.minecraft.screen.render(graphics, 0, 0, partialTick);
        }
        Win95LoadingScreen.render(graphics, this.currentProgress, fade);

        if (f >= 2.0F) {
            this.minecraft.setOverlay(null);
        }
        if (this.fadeOutStart == -1L && this.reload.isDone() && (!this.fadeIn || f1 >= 2.0F)) {
            this.fadeOutStart = Util.getMillis();
            try {
                this.reload.checkExceptions();
                this.onFinish.accept(Optional.empty());
            } catch (Throwable t) {
                this.onFinish.accept(Optional.of(t));
            }
            if (this.minecraft.screen != null) {
                this.minecraft.screen.init(this.minecraft, graphics.guiWidth(), graphics.guiHeight());
            }
        }
        ci.cancel();
    }
}
