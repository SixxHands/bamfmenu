package com.retro.bamfmenu.early.bios.gfx;

import com.retro.bamfmenu.early.bios.gfx.RichFont;
import net.neoforged.fml.earlydisplay.ElementShader;
import net.neoforged.fml.earlydisplay.SimpleBufferBuilder;

import static org.lwjgl.opengl.GL32C.*;

/* The render-thread-only GL text facade + per-frame rendering context every Screen draws through, so
   screens stay free of LWJGL/OpenGL and the provider remains the sole owner of the framebuffer/blit/swap.
   It wraps the provider's shader/font/buffer and exposes the terminal vocabulary screens need: the shader
   preamble (beginFrameShader), text(), the theme-aware colours, the framebuffer size + logical canvas
   math, the layout constants, and the pure string helpers (clip/rule/center). The provider refreshes the
   per-frame state (framebuffer size + theme) via setFrame() once per frame before any screen draws.

   .75: the text core landed in STEP 1; STEP 2 adds the colours / fb-size / layout so screens have a
   complete rendering context. The scissor-cleared popups + the cutscene wipe still live provider-side and
   fold in with their owning screens (they need the per-frame bg-clear the provider's renderFrame owns).
   This is the only early.screen class that touches GL, and it holds zero game state; constructed on the
   render thread in initRender after the font is built. */
public final class TerminalCanvas {

    /* Layout (mirrors the provider's design constants). */
    public static final int LOGICAL_W = 1400; // logical canvas width the body lays out in
    public static final int MARGIN = 16;      // left text margin

    /* Theme palette: the DOS default, or white-on-blue when "You're Blue Now" is on. */
    private static final int FG = 0xFFAAAAAA;      // DOS light gray
    private static final int FG_DIM = 0xFF6E6E6E;  // header / older lines
    private static final int FG_GOLD = 0xFF00D7FF; // gold accent (ABGR)
    private static final float[] BG_BLACK = {0.0F, 0.0F, 0.0F};
    private static final float[] BG_BLUE = {0.0F, 0.0F, 0.66F};

    private final ElementShader shader;
    private final RichFont font;
    private final SimpleBufferBuilder buffer;
    private final int fontTex;
    private final int lineSpacing;
    private final int charWidth;

    /* Per-frame state the provider refreshes via setFrame() before drawing. */
    private int fbWidth = 854;
    private int fbHeight = 480;
    private boolean blueTheme;

    public TerminalCanvas(ElementShader shader, RichFont font, SimpleBufferBuilder buffer, int fontTex) {
        this.shader = shader;
        this.font = font;
        this.buffer = buffer;
        this.fontTex = fontTex;
        this.lineSpacing = font.lineSpacing();
        this.charWidth = Math.max(1, font.stringWidth("0"));
    }

    /* Refresh per-frame framebuffer size + theme. Called once per frame by renderFrame BEFORE any screen
       draws, so colours + the logical->framebuffer mapping are current. */
    public void setFrame(int fbWidth, int fbHeight, boolean blueTheme) {
        this.fbWidth = fbWidth;
        this.fbHeight = fbHeight;
        this.blueTheme = blueTheme;
    }

    public int fbWidth() {
        return fbWidth;
    }

    public int fbHeight() {
        return fbHeight;
    }

    public int logicalW() {
        return LOGICAL_W;
    }

    public int margin() {
        return MARGIN;
    }

    public int lineSpacing() {
        return lineSpacing;
    }

    public int charWidth() {
        return charWidth;
    }

    public boolean blueTheme() {
        return blueTheme;
    }

    /* The logical canvas height for the current framebuffer aspect (logicalW * fbH / fbW). */
    public int logicalH() {
        return Math.max(1, LOGICAL_W * fbHeight / Math.max(1, fbWidth));
    }

    /* ---- theme-aware colours (read the per-frame theme) ---- */
    public int cFg() {
        return blueTheme ? 0xFFFFFFFF : FG;
    }

    public int cBright() {
        return 0xFFFFFFFF;
    }

    public int cDim() {
        return blueTheme ? 0xFFC8C8C8 : FG_DIM;
    }

    public int cGold() {
        return FG_GOLD;
    }

    /* ---- GL text primitives (render thread, inside the provider's bound FBO) ---- */

    /* The per-frame preamble every screen runs before drawing: activate the shader, set the logical canvas
       size, bind the font texture, select the FONT render type. Replaces the 4-line block formerly copied
       into each draw* method. */
    public void beginFrameShader(int logicalW, int logicalH) {
        shader.activate();
        shader.updateScreenSizeUniform(logicalW, logicalH);
        shader.updateTextureUniform(fontTex);
        shader.updateRenderTypeUniform(ElementShader.RenderType.FONT);
    }

    /* Change ONLY the logical canvas size mid-frame (the header draws its logos in a wider canvas, then
       restores the body size). Assumes beginFrameShader already activated the shader this frame. */
    public void logicalSize(int logicalW, int logicalH) {
        shader.updateScreenSizeUniform(logicalW, logicalH);
    }

    /* Draw one run of text at (x,y) in the current logical canvas, in ABGR colour. No-op for null/empty. */
    public void text(int x, int y, String text, int colour) {
        if (text == null || text.isEmpty()) {
            return;
        }
        buffer.begin(SimpleBufferBuilder.Format.POS_TEX_COLOR, SimpleBufferBuilder.Mode.QUADS);
        font.draw(x, y, buffer, text, colour);
        buffer.draw();
    }

    /* The widest line's font-measured pixel width, for centering ASCII-art blocks. */
    public int blockWidth(String[] lines) {
        int w = 1;
        for (String s : lines) {
            w = Math.max(w, font.stringWidth(s));
        }
        return w;
    }

    /* Draw a block of monospace lines UNIFORMLY scaled to fit a logical-coordinate rectangle (rx,ry,rw,rh) and
       centered within it. Because we use the ASCII look by choice (not a real fixed terminal), art does not have
       to match the base text grid: this lets a "pop" (e.g. the rhythm judgment) drop in arbitrarily big/long art
       and always have it fill its own reserved area instead of overflowing the playfield. It works by the
       header-logo trick -- temporarily remap the logical canvas so the SAME font renders bigger/smaller -- then
       restores the body coordinate space before returning, so later draws this frame are unaffected. Aspect is
       preserved (uniform scale); ABGR colour. No-op for empty art or a degenerate rect. */
    public void drawScaledCentered(String[] lines, int rx, int ry, int rw, int rh, int colour) {
        if (lines == null || lines.length == 0 || rw <= 0 || rh <= 0) {
            return;
        }
        int blockW = blockWidth(lines);          // widest line, logical units
        int blockH = lines.length * lineSpacing; // logical units
        if (blockW <= 0 || blockH <= 0) {
            return;
        }
        double s = Math.min(rw / (double) blockW, rh / (double) blockH);
        s = Math.max(0.05, Math.min(8.0, s)); // guard against a degenerate canvas size
        int baseH = logicalH();
        int cw = Math.max(1, (int) Math.round(LOGICAL_W / s));
        int ch = Math.max(1, (int) Math.round(baseH / s));
        double originX = rx + (rw - blockW * s) / 2.0; // centered block top-left, body-logical units
        double originY = ry + (rh - blockH * s) / 2.0;
        logicalSize(cw, ch); // remap: the same glyphs now render at scale s
        try {
            for (int i = 0; i < lines.length; i++) {
                int sx = (int) Math.round(originX / s);
                int sy = (int) Math.round(originY / s + (double) i * lineSpacing);
                text(sx, sy, lines[i], colour);
            }
        } finally {
            logicalSize(LOGICAL_W, baseH); // restore the body coordinate space
        }
    }

    /* A centered modal popup: GL-scissor-clear its rectangle first (the text renderer can't fill a
       background, so without this the screen behind bleeds through), then draw an ASCII-bordered box with
       the content lines; colors[i] colours lines[i], an empty string is a blank row. The scissor rect uses
       the SAME logical->framebuffer mapping the text shader does, so the cleared area lines up with the box. */
    public void popup(int logicalW, int logicalH, String[] lines, int[] colors) {
        int inner = 4;
        for (String l : lines) {
            inner = Math.max(inner, l.length() + 4);
        }
        int boxW = (inner + 2) * charWidth;
        int boxH = (lines.length + 2) * lineSpacing;
        int px = Math.max(MARGIN, (logicalW - boxW) / 2);
        int py = Math.max(0, (logicalH - boxH) / 2);

        int sx = (int) ((double) px / logicalW * fbWidth);
        int sy = (int) ((double) py / logicalH * fbHeight);
        int sw = (int) ((double) boxW / logicalW * fbWidth);
        int sh = (int) ((double) boxH / logicalH * fbHeight);
        glEnable(GL_SCISSOR_TEST);
        glScissor(sx, sy, sw, sh);
        float[] bg = blueTheme ? BG_BLUE : BG_BLACK;
        glClearColor(bg[0], bg[1], bg[2], 1.0F);
        glClear(GL_COLOR_BUFFER_BIT);
        glDisable(GL_SCISSOR_TEST);

        int border = cBright();
        String top = "┌" + "─".repeat(inner) + "┐";
        String bot = "└" + "─".repeat(inner) + "┘";
        text(px, py, top, border);
        for (int i = 0; i < lines.length; i++) {
            text(px, py + (i + 1) * lineSpacing, "│" + center(lines[i], inner) + "│", colors[i]);
        }
        text(px, py + (lines.length + 1) * lineSpacing, bot, border);
    }

    /* Scissor-clear the logical rect [yTop..yBot] from the right edge inward by progress (0..1), so content
       appears to wipe away right-to-left (the cutscene's opening wipe). Uses the SAME logical->framebuffer
       mapping + per-frame theme bg as popup(); the only raw-GL way the cutscene erases the screen, kept here
       so the provider/screens stay GL-free. */
    public void wipeFromRight(int logicalW, int logicalH, int yTop, int yBot, float progress) {
        progress = Math.max(0f, Math.min(1f, progress));
        int lx = (int) (logicalW * (1f - progress));
        int sx = (int) ((double) lx / logicalW * fbWidth);
        int sy = (int) ((double) yTop / logicalH * fbHeight);
        int sw = fbWidth - sx;
        int sh = (int) ((double) (yBot - yTop) / logicalH * fbHeight);
        if (sw <= 0 || sh <= 0) {
            return;
        }
        glEnable(GL_SCISSOR_TEST);
        glScissor(sx, sy, sw, sh);
        float[] bg = blueTheme ? BG_BLUE : BG_BLACK;
        glClearColor(bg[0], bg[1], bg[2], 1.0F);
        glClear(GL_COLOR_BUFFER_BIT);
        glDisable(GL_SCISSOR_TEST);
    }

    /* ---- pure terminal-string helpers (no GL) ---- */

    /* Truncate a line to at most maxChars columns. */
    public static String clip(String s, int maxChars) {
        if (s.length() <= maxChars) {
            return s;
        }
        return s.substring(0, Math.max(0, maxChars));
    }

    /* A full-width horizontal rule of box-drawing characters. */
    public static String rule(int maxChars) {
        return "─".repeat(Math.max(1, maxChars));
    }

    /* Center s within width columns (truncating if longer). */
    public static String center(String s, int width) {
        if (s.length() >= width) {
            return s.substring(0, width);
        }
        int total = width - s.length();
        int left = total / 2;
        return " ".repeat(left) + s + " ".repeat(total - left);
    }

    /* A progress/level bar of block glyphs: full block filled, light shade empty (medium shade for the
       indeterminate pct<0 case). */
    public static String asciiBar(int pct, int width) {
        StringBuilder sb = new StringBuilder(width + 2);
        sb.append('[');
        int filled = pct < 0 ? 0 : Math.max(0, Math.min(width, pct * width / 100));
        for (int i = 0; i < width; i++) {
            sb.append(i < filled ? '█' : (pct < 0 ? '▒' : '░'));
        }
        sb.append(']');
        return sb.toString();
    }
}
