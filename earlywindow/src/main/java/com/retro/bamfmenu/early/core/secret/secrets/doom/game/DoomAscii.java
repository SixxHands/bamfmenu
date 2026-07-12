package com.retro.bamfmenu.early.core.secret.secrets.doom.game;

/* Framebuffer -> colored ASCII, the doom-ascii technique (github.com/wojciech-graj/doom-ascii) on our terminal:
   downsample DOOM's indexed screen into a cols x rows grid; each cell averages its source block to an RGB color
   (drawn true-color by TerminalCanvas.text) and picks a glyph from a dark->bright luminance ramp. Retro graded
   ASCII: the color carries the image, the glyph adds texture (no uniblocks). Headless: no bios/, no GL. */
public final class DoomAscii {

    /** Dark -> bright glyph ramp, the 70-char gradient doom-ascii uses (far finer than a 10-step ramp). */
    private static final char[] RAMP =
            " .'`^\",:;Il!i><~+_-?][}{1)(|\\/tfjrxnuvczXYUJCLQ0OZmwqpdbkhao*#MW&8%B@$".toCharArray();
    /** Sub-samples per axis within each cell's source block (SUB x SUB average), cheap anti-aliasing. */
    private static final int SUB = 2;
    /** sqrt gamma LUT: lifts DOOM's dark reds/browns so the title/levels read on a black terminal (doom-ascii -fixgamma). */
    private static final int[] GAMMA = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            GAMMA[i] = (int) Math.round(Math.sqrt(i / 255.0) * 255.0);
        }
    }

    /** A rendered grid: parallel glyph + 0xRRGGBB color arrays, row-major (index = y*cols + x). */
    public record Rendered(int cols, int rows, char[] glyphs, int[] colors) { }

    public static Rendered render(DoomEngine.Frame f, int cols, int rows) {
        return render(f, cols, rows, 0, f.height());
    }

    /** Downsample only source rows [srcY0, srcY1), used to crop DOOM's pixel status bar out of the in-level view. */
    public static Rendered render(DoomEngine.Frame f, int cols, int rows, int srcY0, int srcY1) {
        byte[] b = f.indices();
        int[] pal = f.palette();
        int w = f.width(), h = f.height();
        srcY0 = Math.max(0, Math.min(srcY0, h - 1));
        srcY1 = Math.max(srcY0 + 1, Math.min(srcY1, h));
        int srcH = srcY1 - srcY0;
        char[] glyphs = new char[cols * rows];
        int[] colors = new int[cols * rows];
        for (int cy = 0; cy < rows; cy++) {
            int sy0 = srcY0 + (int) ((long) cy * srcH / rows);
            int sy1 = srcY0 + (int) ((long) (cy + 1) * srcH / rows);
            for (int cx = 0; cx < cols; cx++) {
                int sx0 = (int) ((long) cx * w / cols);
                int sx1 = (int) ((long) (cx + 1) * w / cols);
                long rr = 0, gg = 0, bb = 0;
                int n = 0;
                for (int s = 0; s < SUB; s++) {
                    int py = sy0 + (sy1 - sy0) * (2 * s + 1) / (2 * SUB);
                    if (py >= h) {
                        py = h - 1;
                    }
                    int rowOff = py * w;
                    for (int t = 0; t < SUB; t++) {
                        int px = sx0 + (sx1 - sx0) * (2 * t + 1) / (2 * SUB);
                        if (px >= w) {
                            px = w - 1;
                        }
                        int rgb = pal[b[rowOff + px] & 0xFF];
                        rr += (rgb >> 16) & 0xFF;
                        gg += (rgb >> 8) & 0xFF;
                        bb += rgb & 0xFF;
                        n++;
                    }
                }
                int r = GAMMA[(int) (rr / n)], g = GAMMA[(int) (gg / n)], bl = GAMMA[(int) (bb / n)];
                // doom-ascii brightness: a FLAT R+G+B sum (perceptual weighting would darken DOOM's reds/browns).
                int gi = (r + g + bl) * RAMP.length / 766;
                if (gi >= RAMP.length) {
                    gi = RAMP.length - 1;
                }
                int idx = cy * cols + cx;
                glyphs[idx] = RAMP[gi];
                // TerminalCanvas.text wants ABGR with full alpha (0xAABBGGRR); DOOM's palette is 0x00RRGGBB, so swap
                // R<->B and force alpha=FF. (Plain 0xRRGGBB renders FULLY TRANSPARENT -> invisible black screen.)
                colors[idx] = 0xFF000000 | (bl << 16) | (g << 8) | r;
            }
        }
        return new Rendered(cols, rows, glyphs, colors);
    }
}
