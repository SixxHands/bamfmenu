package com.retro.bamfmenu.mixin;

import com.retro.bamfmenu.client.Win95LoadingScreen;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.util.Mth;
import net.neoforged.fml.earlydisplay.DisplayWindow;
import net.neoforged.fml.loading.progress.ProgressMeter;
import net.neoforged.neoforge.client.loading.NeoForgeLoadingOverlay;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.function.Consumer;

/* Startup loading screen -> terminal. NeoForge replaces the overlay with NeoForgeLoadingOverlay,
   which fully overrides render() to blit the early-window framebuffer; we cancel that and draw our
   terminal instead, but replicate its exact tail lifecycle (progress smoothing, the fade-out finish
   that calls setOverlay(null)/displayWindow.close(), and the reload-done trigger that fires onFinish
   and re-inits the screen). Skipping that lifecycle would hang the game on the loading screen. */
@Mixin(NeoForgeLoadingOverlay.class)
public abstract class NeoForgeLoadingOverlayMixin {

    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private ReloadInstance reload;
    @Shadow @Final private Consumer<Optional<Throwable>> onFinish;
    @Shadow @Final private DisplayWindow displayWindow;
    @Shadow @Final private ProgressMeter progressMeter;
    @Shadow private float currentProgress;
    @Shadow private long fadeOutStart;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void bamfmenu$terminal(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        long millis = Util.getMillis();
        float fadeOutTimer = this.fadeOutStart > -1L ? (float) (millis - this.fadeOutStart) / 1000.0F : -1.0F;
        this.currentProgress = Mth.clamp(this.currentProgress * 0.95F + this.reload.getActualProgress() * 0.05F, 0.0F, 1.0F);
        this.progressMeter.setAbsolute(Mth.ceil(this.currentProgress * 1000));
        /* Ease-out dissolve over ~2s: opacity drops fast at the start (begins blending into the menu
           immediately) then eases to nothing, staying visible the whole span so it reads as a crossfade
           rather than a cut. */
        float fadeT = Mth.clamp(fadeOutTimer / 2.0F, 0.0F, 1.0F);
        float fade = (1.0F - fadeT) * (1.0F - fadeT);

        if (fadeOutTimer >= 0.0F && this.minecraft.screen != null) {
            this.minecraft.screen.render(graphics, 0, 0, partialTick);
        }
        Win95LoadingScreen.render(graphics, this.currentProgress, fade);

        if (fadeOutTimer >= 2.0F) {
            this.progressMeter.complete();
            this.minecraft.setOverlay(null);
            this.displayWindow.close();
        }
        if (this.fadeOutStart == -1L && this.reload.isDone()) {
            this.fadeOutStart = Util.getMillis();
            try {
                this.reload.checkExceptions();
                this.onFinish.accept(Optional.empty());
            } catch (Throwable t) {
                this.onFinish.accept(Optional.of(t));
            }
            if (this.minecraft.screen != null) {
                this.minecraft.screen.init(this.minecraft, this.minecraft.getWindow().getGuiScaledWidth(), this.minecraft.getWindow().getGuiScaledHeight());
            }
        }
        ci.cancel();
    }
}
