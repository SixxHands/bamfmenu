package com.retro.bamfmenu.early.core.asset;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/* Shared ASCII-art utilities + constants, relocated out of TerminalWindowProvider so song/panel code can
   load and colour art WITHOUT depending on the god-class. First step of the project restructure (relocate
   shared utilities to a neutral public home -- see docs/BAMF_STRUCTURE_PLAN.md ). Pure stdlib, no
   GL/MC, safe in the service jar. Resources resolve from the classpath root exactly as before (the loader
   class doesn't matter -- both are in this one jar). */
public final class Art {

    private Art() {
    }

    /* The four lane colours (ABGR): purple, yellow, green, red. */
    public static final int[] LANE_COLORS = {0xFFFF00AA, 0xFFFFFF00, 0xFF00FF00, 0xFF0000FF};

    /* Load a bundled ASCII-art resource into trimmed lines, sanitizing un-bakeable glyphs to spaces. A
       missing file or read error returns an empty array so callers degrade gracefully. */
    public static String[] loadArt(String resource) {
        try (InputStream in = Art.class.getResourceAsStream(resource)) {
            if (in == null) {
                return new String[0];
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(sanitizeAscii(line));
            }
            while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
                lines.remove(lines.size() - 1);
            }
            while (!lines.isEmpty() && lines.get(0).isBlank()) {
                lines.remove(0);
            }
            return lines.toArray(new String[0]);
        } catch (Throwable t) {
            return new String[0];
        }
    }

    /* True for the glyph ranges RichFont bakes (keep in sync with RichFont.RANGES): ASCII, Latin-1, a few
       punctuation marks, arrows, box-drawing, block elements and a few geometric shapes. Anything else in
       bundled art is replaced with a space (it could not be drawn anyway). */
    private static boolean isDrawable(char c) {
        return (c >= 0x20 && c <= 0x7E)
                || (c >= 0xA0 && c <= 0xFF)
                || (c >= 0x2010 && c <= 0x2027)
                || (c >= 0x2190 && c <= 0x2193)
                || (c >= 0x2500 && c <= 0x259F)
                || (c >= 0x25A0 && c <= 0x25CF);
    }

    private static String sanitizeAscii(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(isDrawable(c) ? c : ' ');
        }
        int end = b.length();
        while (end > 0 && b.charAt(end - 1) == ' ') {
            end--;
        }
        return b.substring(0, end);
    }
}
