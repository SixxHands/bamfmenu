package com.retro.bamfmenu.early.core.asset;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;

/* A bundled PICTURE (png etc.) -> colored ASCII cells, for showing an actual image inside a terminal box (the wysi
   image cutscene). Same technique as core/secret/doom's DoomAscii (block-average a region per cell, sqrt gamma, flat R+G+B
   brightness picks a ramp glyph, color = the averaged RGB as opaque ABGR) but fed from any decoded image instead of
   DOOM's framebuffer, and AUTO-FIT: the image scales to the target cell box preserving its aspect on the non-square
   cell grid, so swapping the picture file needs no code change. Decode is JDK ImageIO (headless-safe, no window;
   the vendored DOOM engine already uses AWT classes this way in the shipped early window). The Source wrapper keeps
   AWT types out of callers' imports. Headless: no bios/, no GL. */
public final class AsciiImage {

    /** Dark -> bright glyph ramp (doom-ascii's 70-char gradient, same as DoomAscii). */
    private static final char[] RAMP =
            " .'`^\",:;Il!i><~+_-?][}{1)(|\\/tfjrxnuvczXYUJCLQ0OZmwqpdbkhao*#MW&8%B@$".toCharArray();
    /** Sub-samples per axis within each cell's source block (SUB x SUB average). */
    private static final int SUB = 2;
    /** sqrt gamma LUT: lifts dark tones so pictures read on a black terminal. */
    private static final int[] GAMMA = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            GAMMA[i] = (int) Math.round(Math.sqrt(i / 255.0) * 255.0);
        }
    }

    /** An opaque handle for a decoded image, so callers never import AWT types. */
    public record Source(BufferedImage image) { }

    /** A Source's raw ARGB pixels + dimensions, for a REAL-bitmap renderer (the full-quality cutscene path). */
    public static int[] argb(Source src) {
        BufferedImage img = src.image();
        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }

    public static int width(Source src) {
        return src.image().getWidth();
    }

    public static int height(Source src) {
        return src.image().getHeight();
    }

    /** A rendered grid: parallel glyph + opaque-ABGR color arrays, row-major (index = y*cols + x). */
    public record Rendered(int cols, int rows, char[] glyphs, int[] colors) { }

    /** Decode a bundled image resource (e.g. "/wysi.png"). Null if missing/unreadable (caller shows a notice). */
    public static Source load(String resourcePath) {
        try (InputStream in = AsciiImage.class.getResourceAsStream(resourcePath)) {
            BufferedImage img = in == null ? null : ImageIO.read(in);
            return img == null ? null : new Source(img);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Full-brightness render (see the brightness overload). */
    public static Rendered render(Source src, int maxCols, int maxRows, int cellW, int cellH) {
        return render(src, maxCols, maxRows, cellW, cellH, 1.0);
    }

    /** Fit + downsample into at most maxCols x maxRows cells of cellW x cellH px, preserving the image's aspect on
        the tall cell grid (a square image spans ~cellH/cellW more columns than rows). Transparency composites on
        black (the terminal background). `brightness` (0..1) scales the pixels BEFORE the gamma + glyph ramp, so a
        fade resolves THROUGH the ASCII gradient (faint dots -> dense bright glyphs), the terminal-native fade,
        rather than just dimming fixed glyphs. */
    public static Rendered render(Source src, int maxCols, int maxRows, int cellW, int cellH, double brightness) {
        double k = Math.max(0.0, Math.min(1.0, brightness));
        BufferedImage img = src.image();
        int w = img.getWidth(), h = img.getHeight();
        double cellAspect = ((double) w / h) * cellH / Math.max(1, cellW); // image aspect in CELL counts
        int rows = Math.max(1, maxRows);
        int cols = Math.max(1, (int) Math.round(rows * cellAspect));
        if (cols > maxCols) {
            cols = Math.max(1, maxCols);
            rows = Math.max(1, (int) Math.round(cols / cellAspect));
        }
        char[] glyphs = new char[cols * rows];
        int[] colors = new int[cols * rows];
        for (int cy = 0; cy < rows; cy++) {
            int sy0 = (int) ((long) cy * h / rows), sy1 = (int) ((long) (cy + 1) * h / rows);
            for (int cx = 0; cx < cols; cx++) {
                int sx0 = (int) ((long) cx * w / cols), sx1 = (int) ((long) (cx + 1) * w / cols);
                long rr = 0, gg = 0, bb = 0;
                int n = 0;
                for (int s = 0; s < SUB; s++) {
                    int py = sy0 + Math.max(0, sy1 - sy0) * (2 * s + 1) / (2 * SUB);
                    if (py >= h) {
                        py = h - 1;
                    }
                    for (int t = 0; t < SUB; t++) {
                        int px = sx0 + Math.max(0, sx1 - sx0) * (2 * t + 1) / (2 * SUB);
                        if (px >= w) {
                            px = w - 1;
                        }
                        int argb = img.getRGB(px, py);
                        int a = (argb >>> 24) & 0xFF;
                        rr += ((argb >> 16) & 0xFF) * a / 255;
                        gg += ((argb >> 8) & 0xFF) * a / 255;
                        bb += (argb & 0xFF) * a / 255;
                        n++;
                    }
                }
                int r = GAMMA[(int) (rr * k / n)], g = GAMMA[(int) (gg * k / n)], bl = GAMMA[(int) (bb * k / n)];
                int gi = (r + g + bl) * RAMP.length / 766;
                if (gi >= RAMP.length) {
                    gi = RAMP.length - 1;
                }
                int idx = cy * cols + cx;
                glyphs[idx] = RAMP[gi];
                // opaque ABGR for TerminalCanvas (0xAABBGGRR; plain 0xRRGGBB renders fully transparent)
                colors[idx] = 0xFF000000 | (bl << 16) | (g << 8) | r;
            }
        }
        return new Rendered(cols, rows, glyphs, colors);
    }

    private AsciiImage() {
    }
}
