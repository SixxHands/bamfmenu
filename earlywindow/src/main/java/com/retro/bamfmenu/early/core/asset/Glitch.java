package com.retro.bamfmenu.early.core.asset;

/* The shared glitch-text primitive: the alphabet + helpers behind the recovery cutscene's scramble/noise effect,
   extracted so the hidden Snake game can reuse the EXACT same look (a per-frame shimmer of random symbols) instead
   of duplicating it. Pure char logic (no GL, no Minecraft); Math.random per call, so render-thread only. */
public final class Glitch {

    /* The glitch alphabet: printable symbols only (no letters/digits), so it reads as "static". */
    public static final String CHARS = "!@#$%^&*()_+-=[]{}<>/\\|?~:;";

    private Glitch() {
    }

    /* One random glitch character (re-roll each call for a per-frame shimmer). */
    public static char glyph() {
        return CHARS.charAt((int) (Math.random() * CHARS.length()));
    }

    /* A string of `len` random glitch characters (e.g. a glitching snake segment or a full row of noise). */
    public static String noise(int len) {
        if (len <= 0) {
            return "";
        }
        char[] b = new char[len];
        for (int i = 0; i < b.length; i++) {
            b[i] = glyph();
        }
        return new String(b);
    }

    /* A left-to-right text-decode reveal: over [0,1] `progress`, the left `progress` fraction of `target` shows its
       real characters and the rest spin through random glitch glyphs (spaces always stay blank). The recovery
       cutscene's "unzipping" flourish; here so any screen can reuse it. */
    public static String scramble(String target, float progress) {
        if (target == null) {
            return "";
        }
        if (progress >= 1f) {
            return target;
        }
        int locked = (int) (Math.max(0f, progress) * target.length());
        StringBuilder sb = new StringBuilder(target.length());
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            sb.append(i < locked || c == ' ' ? c : glyph());
        }
        return sb.toString();
    }
}
