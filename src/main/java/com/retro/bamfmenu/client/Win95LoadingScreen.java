package com.retro.bamfmenu.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.fml.loading.progress.ProgressMeter;
import net.neoforged.fml.loading.progress.StartupNotificationManager;

import java.io.InputStream;
import java.util.List;

/* The in-game (resource reload) loading screen, styled as a classic Windows 95 setup dialog: a teal
   desktop, a centered raised gray dialog with a navy title bar (+ the program icon), a status label
   with the real loading text, and a sunken progress trough filled with the classic blue segmented
   blocks. This is deliberately the GUI-era counterpart to the early window's DOS terminal: boot =
   terminal, game load = Win95 dialog. Progress comes from the overlay (accurate); the status text
   comes from FancyModLoader's StartupNotificationManager. All text uses PixelFont (the embedded
   Monocraft bitmap) so it is readable from the first frame -- Minecraft's own Font is not loaded yet
   this early in the reload. Fades with the overlay. */
public final class Win95LoadingScreen {

    private static final int TEAL = 0x008080;
    private static final int FACE = 0xC0C0C0;
    private static final int LIGHT = 0xFFFFFF;
    private static final int SHADOW = 0x808080;
    private static final int DARK = 0x404040;
    private static final int NAVY = 0x000080;
    private static final int BLUE = 0x0000A8;
    private static final int BLACK = 0x000000;
    private static final int WHITE = 0xFFFFFF;

    /* Optional full-screen background image behind the dialog ("loading boxes over an image"). Drop a
       PNG at this path to use it; otherwise it falls back to the plain teal Win95 desktop. */
    private static final ResourceLocation BG = ResourceLocation.fromNamespaceAndPath("bamfmenu", "textures/gui/loading_bg.png");
    private static final String BG_CLASSPATH = "/assets/bamfmenu/textures/gui/loading_bg.png";
    private static int bgState; // 0 unknown, 1 ok, 2 missing
    private static int bgW;
    private static int bgH;

    private Win95LoadingScreen() {}

    public static void render(GuiGraphics g, float progress, float fade) {
        int a = Mth.clamp((int) (fade * 255.0F), 0, 255);
        if (a <= 4) {
            return;
        }
        progress = Mth.clamp(progress, 0.0F, 1.0F);
        int w = g.guiWidth();
        int h = g.guiHeight();

        /* The whole loading screen -- desktop, dialog and all -- is drawn at the overlay's fade alpha
           (a), so it ALL fades out together. The loading overlay renders the full title screen (menu
           background + logo + buttons) behind us during its fade-out, so as we fade out the complete
           menu fades in at the same time -- one unified crossfade, no element popping in. */
        if (!drawBackground(g, w, h, a)) {
            g.fill(0, 0, w, h, argb(TEAL, a)); // plain Win95 desktop fallback
        }

        /* All text here uses PixelFont (the embedded Monocraft bitmap), NOT Minecraft's Font: this dialog
           is shown DURING the resource reload that builds MC's font atlas, so MC text renders as
           missing-glyph boxes early in the reload. PixelFont depends on nothing but its baked table, so
           the status is readable from the very first frame -- which is the whole point. */
        int dw = Math.min(w - 40, 280);
        int dh = 78;
        int dx = (w - dw) / 2;
        int dy = (h - dh) / 2;

        /* raised dialog */
        g.fill(dx, dy, dx + dw, dy + dh, argb(FACE, a));
        g.fill(dx, dy, dx + dw, dy + 1, argb(LIGHT, a));
        g.fill(dx, dy, dx + 1, dy + dh, argb(LIGHT, a));
        g.fill(dx, dy + dh - 1, dx + dw, dy + dh, argb(DARK, a));
        g.fill(dx + dw - 1, dy, dx + dw, dy + dh, argb(DARK, a));
        g.fill(dx + 1, dy + dh - 2, dx + dw - 1, dy + dh - 1, argb(SHADOW, a));
        g.fill(dx + dw - 2, dy + 1, dx + dw - 1, dy + dh - 1, argb(SHADOW, a));

        /* title bar */
        int tbx = dx + 3, tby = dy + 3, tbw = dw - 6, tbh = 14;
        g.fill(tbx, tby, tbx + tbw, tby + tbh, argb(NAVY, a));
        int tx = tbx + 3;
        ResourceLocation icon = WindowIconHandler.chosenIcon();
        if (icon != null) {
            int iw = WindowIconHandler.chosenWidth();
            int ih = WindowIconHandler.chosenHeight();
            if (iw > 0 && ih > 0) {
                /* Same blit/blend caveat as the background: the POSITION_TEX blit path honours the
                   setColor alpha only with blend enabled, so without this the title-bar icon stayed
                   opaque and cut out abruptly instead of fading with the rest of the window. */
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                g.setColor(1.0F, 1.0F, 1.0F, a / 255.0F);
                g.blit(icon, tbx + 2, tby + 1, 12, 12, 0.0F, 0.0F, iw, ih, iw, ih);
                g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
                RenderSystem.disableBlend();
                tx = tbx + 2 + 12 + 3;
            }
        }
        PixelFont.draw(g, "BAMF Setup", tx, tby + (tbh - PixelFont.lineHeight(1)) / 2 + 1, 1, argb(WHITE, a));

        /* status label + percentage */
        int bodyX = dx + 10;
        int labelY = tby + tbh + 9;
        PixelFont.draw(g, PixelFont.clipToWidth(activeStatus(), dw - 50, 1), bodyX, labelY, 1, argb(BLACK, a));
        String pct = (int) (progress * 100.0F) + "%";
        PixelFont.draw(g, pct, dx + dw - 10 - PixelFont.width(pct, 1), labelY, 1, argb(BLACK, a));

        /* sunken progress trough + blue segmented blocks */
        int barX = bodyX;
        int barY = labelY + 13;
        int barW = dw - 20;
        int barH = 14;
        g.fill(barX, barY, barX + barW, barY + barH, argb(WHITE, a));
        g.fill(barX, barY, barX + barW, barY + 1, argb(DARK, a));
        g.fill(barX, barY, barX + 1, barY + barH, argb(DARK, a));
        g.fill(barX, barY + barH - 1, barX + barW, barY + barH, argb(LIGHT, a));
        g.fill(barX + barW - 1, barY, barX + barW, barY + barH, argb(LIGHT, a));
        int innerX = barX + 2;
        int innerY = barY + 2;
        int innerW = barW - 4;
        int innerH = barH - 4;
        int block = 5;
        int gap = 2;
        int total = Math.max(1, (innerW + gap) / (block + gap));
        int filled = (int) (progress * total);
        for (int i = 0; i < filled; i++) {
            int bx = innerX + i * (block + gap);
            g.fill(bx, innerY, Math.min(bx + block, innerX + innerW), innerY + innerH, argb(BLUE, a));
        }
    }

    private static boolean drawBackground(GuiGraphics g, int w, int h, int alpha) {
        if (bgState == 0) {
            try (InputStream in = Win95LoadingScreen.class.getResourceAsStream(BG_CLASSPATH)) {
                if (in == null) {
                    bgState = 2;
                } else {
                    byte[] hdr = in.readNBytes(24);
                    if (hdr.length < 24) {
                        bgState = 2;
                    } else {
                        bgW = beInt(hdr, 16);
                        bgH = beInt(hdr, 20);
                        bgState = (bgW > 0 && bgH > 0) ? 1 : 2;
                    }
                }
            } catch (Exception e) {
                bgState = 2;
            }
        }
        if (bgState != 1) {
            return false;
        }
        /* The GuiGraphics.blit path uses the POSITION_TEX shader: its ColorModulator honours the
           setColor alpha, but that innerBlit overload does NOT enable blending (unlike fill(), which
           is why the dialog faded while the image didn't -- it was drawn opaque, then cut at a<=4).
           Enable blend explicitly so the alpha actually composites the image over the menu behind it,
           giving a real crossfade. Reset state afterwards so we don't leak it into later draws. */
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.setColor(1.0F, 1.0F, 1.0F, alpha / 255.0F);
        g.blit(BG, 0, 0, w, h, 0.0F, 0.0F, bgW, bgH, bgW, bgH);
        g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
        return true;
    }

    private static int beInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static String activeStatus() {
        try {
            List<StartupNotificationManager.AgeMessage> messages = StartupNotificationManager.getMessages();
            if (!messages.isEmpty()) {
                String t = messages.get(0).message().getText();
                if (t != null && !t.isBlank()) {
                    return t.trim();
                }
            }
            List<ProgressMeter> bars = StartupNotificationManager.getCurrentProgress();
            if (!bars.isEmpty()) {
                return bars.get(0).name() + "...";
            }
        } catch (Throwable ignored) {
        }
        return "Please wait...";
    }

    private static int argb(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }
}
