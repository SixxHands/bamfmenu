package com.retro.bamfmenu.early.bios.gfx;

import static org.lwjgl.opengl.GL32C.*;
import static org.lwjgl.stb.STBTruetype.*;
import static org.lwjgl.system.MemoryUtil.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import net.neoforged.fml.earlydisplay.SimpleBufferBuilder;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBTTAlignedQuad;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTTPackContext;
import org.lwjgl.stb.STBTTPackRange;
import org.lwjgl.stb.STBTTPackedchar;

/* Our own bitmap-font baker for the early window, so we are NOT limited to FML SimpleFont's ASCII-only
   atlas (SimpleFont bakes only codepoints 32..126 AND re-encodes every string to US-ASCII, collapsing any
   non-ASCII to '?'). RichFont bakes a bundled TTF (Consolas, for the authentic Windows look) over SEVERAL
   Unicode ranges (printable ASCII plus box-drawing, block-element/shade and arrow glyphs) into one
   GL_RED atlas via STBTruetype, and draws BY CODEPOINT. It reuses FML's public SimpleBufferBuilder +
   ElementShader.RenderType.FONT to draw, so the GL path is identical to SimpleFont's; only the glyph set and
   lookup differ. The baked quads use the SAME baseline convention as SimpleFont (origin at y = fontSize), so
   existing draw positions are unchanged. Self-contained (LWJGL + java stdlib only); must be built on the
   render thread with the GL context current, like SimpleFont. */
public final class RichFont {

    /* Codepoint ranges to bake (first .. lastInclusive). Kept in sync with the art sanitizer in
       TerminalWindowProvider so bundled art may use the same glyphs. Codepoints a font lacks are skipped by
       STB (advance 0) and left unmapped -> drawn as a blank advance. */
    static final int[][] RANGES = {
            {0x0020, 0x007E}, // printable ASCII
            {0x00A0, 0x00FF}, // Latin-1 supplement (degree, middot, etc.)
            {0x2010, 0x2027}, // dashes, quotes, bullet, ellipsis
            {0x2190, 0x2193}, // arrows: left up right down
            {0x2500, 0x257F}, // box drawing
            {0x2580, 0x259F}, // block elements + shades
            {0x25A0, 0x25CF}, // a few geometric shapes (square, triangle, circle)
    };

    private record Glyph(int advance, int x0, int y0, int x1, int y1, float u0, float v0, float u1, float v1) {}

    private final int textureUnit;
    private final int lineSpacing;
    private final int descent;
    private final int spaceAdvance;
    private final Map<Integer, Glyph> glyphs = new HashMap<>();

    public RichFont(String resource, int fontSize, int textureUnit, int texW, int texH, boolean smooth) {
        this.textureUnit = textureUnit;
        ByteBuffer ttf = readResource(resource);
        try {
            STBTTFontinfo info = STBTTFontinfo.create();
            if (!stbtt_InitFont(info, ttf)) {
                throw new IllegalStateException("RichFont: bad TTF " + resource);
            }
            float[] ascent = new float[1];
            float[] desc = new float[1];
            float[] gap = new float[1];
            stbtt_GetScaledFontVMetrics(ttf, 0, fontSize, ascent, desc, gap);
            this.lineSpacing = (int) (ascent[0] - desc[0] + gap[0]);
            this.descent = (int) Math.floor(desc[0]);

            int fontTextureId = glGenTextures();
            glActiveTexture(GL_TEXTURE0 + textureUnit);
            glBindTexture(GL_TEXTURE_2D, fontTextureId);

            ByteBuffer bitmap = BufferUtils.createByteBuffer(texW * texH);
            STBTTPackedchar.Buffer[] charData = new STBTTPackedchar.Buffer[RANGES.length];
            try (STBTTPackContext pc = STBTTPackContext.malloc()) {
                stbtt_PackBegin(pc, bitmap, texW, texH, 0, 1, NULL);
                stbtt_PackSetOversampling(pc, 1, 1);
                stbtt_PackSetSkipMissingCodepoints(pc, true);
                STBTTPackRange.Buffer ranges = STBTTPackRange.malloc(RANGES.length);
                try {
                    for (int i = 0; i < RANGES.length; i++) {
                        int count = RANGES[i][1] - RANGES[i][0] + 1;
                        STBTTPackedchar.Buffer cb = STBTTPackedchar.malloc(count);
                        charData[i] = cb;
                        ranges.get(i).set((float) fontSize, RANGES[i][0], null, count, cb, (byte) 1, (byte) 1);
                    }
                    stbtt_PackFontRanges(pc, ttf, 0, ranges);
                } finally {
                    ranges.free();
                }
                stbtt_PackEnd(pc);
            }

            glTexImage2D(GL_TEXTURE_2D, 0, GL_RED, texW, texH, 0, GL_RED, GL_UNSIGNED_BYTE, bitmap);
            /* Pixel fonts (Terminus, gohufont) look best NEAREST (crisp, "hard" terminal edges); smooth
               vector fonts use LINEAR. This is the main lever for how hard the text hits. */
            int filter = smooth ? GL_LINEAR : GL_NEAREST;
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
            glActiveTexture(GL_TEXTURE0);

            try (STBTTAlignedQuad q = STBTTAlignedQuad.malloc()) {
                float[] x = new float[1];
                float[] y = new float[1];
                for (int i = 0; i < RANGES.length; i++) {
                    STBTTPackedchar.Buffer cb = charData[i];
                    int count = RANGES[i][1] - RANGES[i][0] + 1;
                    for (int j = 0; j < count; j++) {
                        x[0] = 0f;
                        y[0] = fontSize;
                        stbtt_GetPackedQuad(cb, texW, texH, j, x, y, q, true);
                        int advance = (int) x[0];
                        if (advance <= 0) {
                            continue; // codepoint absent from the font -> leave unmapped
                        }
                        glyphs.put(RANGES[i][0] + j, new Glyph(
                                advance,
                                (int) q.x0(), (int) q.y0(), (int) q.x1(), (int) q.y1(),
                                q.s0(), q.t0(), q.s1(), q.t1()));
                    }
                    cb.free();
                }
            }
            Glyph sp = glyphs.get(0x20);
            this.spaceAdvance = sp != null ? sp.advance() : Math.max(1, fontSize / 2);
        } finally {
            memFree(ttf);
        }
    }

    public int lineSpacing() {
        return lineSpacing;
    }

    int descent() {
        return descent;
    }

    int textureUnit() {
        return textureUnit;
    }

    /* Pixel width of a run, matching draw()'s advance model (newline/tab contribute 0, like SimpleFont). */
    public int stringWidth(String text) {
        int len = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\t') {
                continue;
            }
            if (c == ' ') {
                len += spaceAdvance;
                continue;
            }
            Glyph g = glyphs.get((int) c);
            len += g != null ? g.advance() : spaceAdvance;
        }
        return len;
    }

    /* Emit quads for one text run into bb (which the caller has begun in POS_TEX_COLOR/QUADS with our atlas
       bound and the FONT render type active). Iterates by char, every baked range is in the BMP. Same
       baseline + advance model as SimpleFont, so existing draw coordinates are unchanged. */
    public void draw(int x, int y, SimpleBufferBuilder bb, String text, int colour) {
        int startX = x;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                x = startX;
                y += lineSpacing;
                continue;
            }
            if (c == '\t') {
                x += spaceAdvance * 4;
                continue;
            }
            if (c == ' ') {
                x += spaceAdvance;
                continue;
            }
            Glyph g = glyphs.get((int) c);
            if (g == null) {
                x += spaceAdvance; // unknown glyph -> advance like a space, draw nothing
                continue;
            }
            int x0 = x + g.x0();
            int y0 = y + g.y0();
            int x1 = x + g.x1();
            int y1 = y + g.y1();
            bb.pos(x0, y0).tex(g.u0(), g.v0()).colour(colour).endVertex();
            bb.pos(x1, y0).tex(g.u1(), g.v0()).colour(colour).endVertex();
            bb.pos(x0, y1).tex(g.u0(), g.v1()).colour(colour).endVertex();
            bb.pos(x1, y1).tex(g.u1(), g.v1()).colour(colour).endVertex();
            x += g.advance();
        }
    }

    private static ByteBuffer readResource(String resource) {
        try (InputStream in = RichFont.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("RichFont: resource not found " + resource);
            }
            byte[] bytes = in.readAllBytes();
            ByteBuffer buf = memAlloc(bytes.length);
            buf.put(bytes);
            buf.flip();
            return buf;
        } catch (IOException e) {
            throw new IllegalStateException("RichFont: failed to read " + resource, e);
        }
    }
}
