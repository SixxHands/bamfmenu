package com.retro.bamfmenu.early.core.tools.probe;

import com.retro.bamfmenu.early.core.secret.secrets.doom.game.DoomAscii;
import com.retro.bamfmenu.early.core.secret.secrets.doom.game.DoomEngine;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

/* DEV-ONLY (gradlew :earlywindow:genDoomImage, java.awt.headless=true): boot DOOM to the title screen, then write PNGs
   so the result can be inspected visually (off-screen AWT rendering is fine headless):
     doom-raw.png:       framebuffer with palette read as 0xRRGGBB (R = high byte)
     doom-raw-swap.png:  framebuffer with R/B swapped (to settle the channel order vs the real DOOM title)
     doom-ascii.png:     the DoomAscii grid rasterized as colored monospace glyphs (what the terminal shows)
   Never referenced by the mod; harmless dead code. */
public final class DoomImageProbe {

    public static void main(String[] args) throws Exception {
        DoomEngine eng = new DoomEngine();
        eng.start();
        long t = System.currentTimeMillis();
        while (!eng.ready() && !eng.failed() && System.currentTimeMillis() - t < 20000) {
            Thread.sleep(30);
        }
        Thread.sleep(2500); // let the title screen settle (past any fade-in)
        DoomEngine.Frame f = eng.frame();
        eng.stop();
        if (f == null) {
            System.out.println("[img] no frame");
            System.exit(1);
        }
        int w = f.width(), h = f.height();
        byte[] idx = f.indices();
        int[] pal = f.palette(); // pal[i] = getBaseColor(i) & 0xFFFFFF
        System.out.printf("[img] frame %dx%d; sample palette entries: 16=%06X 96=%06X 176=%06X 256-1=%06X%n",
                w, h, pal[16], pal[96], pal[176], pal[255]);

        // 1) raw framebuffer, palette read as 0xRRGGBB
        BufferedImage raw = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        // 2) raw with R/B swapped
        BufferedImage rawSwap = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = pal[idx[y * w + x] & 0xFF] & 0xFFFFFF;
                raw.setRGB(x, y, c);
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                rawSwap.setRGB(x, y, (b << 16) | (g << 8) | r);
            }
        }
        ImageIO.write(raw, "png", new File("doom-raw.png"));
        ImageIO.write(rawSwap, "png", new File("doom-raw-swap.png"));

        // 3) the DoomAscii grid rasterized as colored monospace glyphs (mimics the terminal)
        int cols = 168, rows = 66;
        DoomAscii.Rendered r = DoomAscii.render(f, cols, rows);
        int cellW = 7, cellH = 11;
        BufferedImage img = new BufferedImage(cols * cellW, rows * cellH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = img.createGraphics();
        g2.setColor(Color.BLACK);
        g2.fillRect(0, 0, img.getWidth(), img.getHeight());
        g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        for (int yy = 0; yy < rows; yy++) {
            for (int xx = 0; xx < cols; xx++) {
                int i = yy * cols + xx;
                int abgr = r.colors()[i];
                int cr = abgr & 0xFF, cg = (abgr >> 8) & 0xFF, cb = (abgr >> 16) & 0xFF; // ABGR -> RGB for AWT
                g2.setColor(new Color(cr, cg, cb));
                g2.drawString(String.valueOf(r.glyphs()[i]), xx * cellW, yy * cellH + cellH - 2);
            }
        }
        g2.dispose();
        ImageIO.write(img, "png", new File("doom-ascii.png"));
        System.out.println("[img] wrote doom-raw.png, doom-raw-swap.png, doom-ascii.png");
        System.exit(0);
    }
}
