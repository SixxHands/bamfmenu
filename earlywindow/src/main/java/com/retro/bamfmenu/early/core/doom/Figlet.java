package com.retro.bamfmenu.early.core.doom;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/* A tiny on-the-fly FIGlet renderer (the "TAAG engine", in Java) for the DOOM menus: parse a .flf font file + turn any
   string into big ASCII-art letters made of the small terminal characters. This is the terminal-correct way to make
   text big -- the letterforms are composed of the native glyphs, NOT a scaled-up font. Full-width layout (no smushing):
   simple, deterministic, fine for menu labels. core/ layer; loads fonts from /doom/fonts/<name>.flf on the classpath. */
public final class Figlet {

    private final int height;
    private final Map<Character, String[]> glyphs;
    private static final Map<String, Figlet> CACHE = new HashMap<>();

    private Figlet(int height, Map<Character, String[]> glyphs) {
        this.height = height;
        this.glyphs = glyphs;
    }

    public int height() {
        return height;
    }

    /** Load a bundled font (/doom/fonts/<name>.flf), cached. Returns null if missing/unparseable (caller falls back). */
    public static Figlet font(String name) {
        Figlet f = CACHE.get(name);
        if (f != null) {
            return f;
        }
        try (InputStream in = Figlet.class.getResourceAsStream("/doom/fonts/" + name + ".flf")) {
            if (in == null) {
                return null;
            }
            f = parse(in);
            CACHE.put(name, f);
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    /** Render text -> `height` rows of ASCII art (uppercased; unknown chars render blank). */
    public String[] render(String text) {
        StringBuilder[] rows = new StringBuilder[height];
        for (int r = 0; r < height; r++) {
            rows[r] = new StringBuilder();
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = Character.toUpperCase(text.charAt(i));
            String[] g = glyphs.get(ch);
            if (g == null) {
                g = glyphs.get(' ');
            }
            if (g == null) {
                continue;
            }
            for (int r = 0; r < height; r++) {
                rows[r].append(r < g.length ? g[r] : "");
            }
        }
        String[] out = new String[height];
        for (int r = 0; r < height; r++) {
            out[r] = rows[r].toString();
        }
        return out;
    }

    /** Width (cols) of the widest row of a rendered block. */
    public static int width(String[] art) {
        int w = 0;
        for (String s : art) {
            w = Math.max(w, s.length());
        }
        return w;
    }

    public static Figlet parse(InputStream in) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String l;
            while ((l = br.readLine()) != null) {
                lines.add(l);
            }
        }
        String[] tk = lines.get(0).trim().split("\\s+");
        char hardblank = tk[0].charAt(tk[0].length() - 1);
        int height = Integer.parseInt(tk[1]);
        int comments = Integer.parseInt(tk[5]);
        int idx = 1 + comments;
        Map<Character, String[]> glyphs = new HashMap<>();
        for (int code = 32; code <= 126 && idx + height <= lines.size(); code++) {
            String[] rows = new String[height];
            for (int r = 0; r < height; r++) {
                rows[r] = strip(lines.get(idx++), hardblank);
            }
            glyphs.put((char) code, rows);
        }
        return new Figlet(height, glyphs);
    }

    private static String strip(String line, char hardblank) {
        if (line.isEmpty()) {
            return "";
        }
        char endmark = line.charAt(line.length() - 1);
        int end = line.length();
        while (end > 0 && line.charAt(end - 1) == endmark) {
            end--;
        }
        return line.substring(0, end).replace(hardblank, ' ');
    }
}
