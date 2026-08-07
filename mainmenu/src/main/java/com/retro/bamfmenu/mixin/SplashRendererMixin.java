package com.retro.bamfmenu.mixin;

import com.mojang.math.Axis;
import com.retro.bamfmenu.client.BamfSplashes;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/* Splash rendering, extended for two things vanilla can't do: per-line custom colors (kept out of the
   displayed text via a #RRGGBB| prefix in the file) and multi-line splashes (authored with \n, decoded
   by BamfSplashes). Vanilla SplashRenderer.render draws ONE centered line inside a translate/rotate/
   pulse pose; we reproduce that exact pose, but scale to the WIDEST line (not the whole concatenated
   string, which would shrink the text) and stack every line centered on the same anchor vanilla uses
   for a single line, so a one-line splash renders pixel-identical to vanilla. We take over the whole
   method (HEAD + cancel) rather than chaining @ModifyArg/@Redirect, because the line count, the width
   used for scaling, and the per-line draw all change together. */
@Mixin(SplashRenderer.class)
public class SplashRendererMixin {

    @Shadow
    @Final
    private String splash;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void bamfmenu$multilineRender(GuiGraphics graphics, int screenWidth, Font font, int alpha, CallbackInfo ci) {
        if (this.splash == null) {
            return; // defensive: let vanilla run if somehow there is no splash
        }

        /* Split on the newlines BamfSplashes decoded from \n; trim each line so stray authoring spaces
           don't bias the centering, drop blank lines, and track the widest line for the scale. */
        String[] raw = this.splash.split("\n", -1);
        List<String> lines = new ArrayList<>(raw.length);
        int maxWidth = 0;
        for (String r : raw) {
            String line = r.trim();
            if (line.isEmpty()) {
                continue;
            }
            lines.add(line);
            int wdt = font.width(line);
            if (wdt > maxWidth) {
                maxWidth = wdt;
            }
        }
        if (lines.isEmpty()) {
            ci.cancel(); // nothing renderable; also skip vanilla drawing an empty string
            return;
        }

        /* Per-line color: custom RGB if this splash has one, else vanilla yellow (0xFFFF00). Keep the
           overlay's alpha byte so the title-screen fade still applies, matching vanilla's color math. */
        Integer custom = BamfSplashes.colorFor(this.splash);
        int rgb = (custom != null) ? (custom & 0x00FFFFFF) : 0x00FFFF00;
        int color = rgb | (alpha & 0xFF000000);

        /* Vanilla's pose, reproduced exactly: translate to the top-right of the logo, rotate -20
           degrees, and apply the per-frame pulse. Only the scale's denominator changes (widest line
           instead of the full string) so a multi-line splash fills the same horizontal budget. */
        graphics.pose().pushPose();
        graphics.pose().translate((float) screenWidth / 2.0F + 123.0F, 69.0F, 0.0F);
        graphics.pose().mulPose(Axis.ZP.rotationDegrees(-20.0F));
        float f = 1.8F - Mth.abs(Mth.sin((float) (Util.getMillis() % 1000L) / 1000.0F * (float) (Math.PI * 2)) * 0.1F);
        f = f * 100.0F / (float) (maxWidth + 32);
        graphics.pose().scale(f, f, f);

        /* Stack the lines centered on the same point vanilla centers its single line on (y = -8): for N
           lines the block top shifts up by (N-1)*lineHeight/2 so the block's middle stays put, and a
           one-line splash lands at exactly -8 (pixel-identical to vanilla). */
        int lineHeight = font.lineHeight;
        int top = -8 - ((lines.size() - 1) * lineHeight) / 2;
        for (int i = 0; i < lines.size(); i++) {
            graphics.drawCenteredString(font, lines.get(i), 0, top + i * lineHeight, color);
        }
        graphics.pose().popPose();
        ci.cancel();
    }
}
