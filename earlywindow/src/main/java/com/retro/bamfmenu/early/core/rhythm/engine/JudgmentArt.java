package com.retro.bamfmenu.early.core.rhythm.engine;

import com.retro.bamfmenu.early.core.asset.Art;

/* Judgment "pop" banners for the rhythm game: each grade is its own editable ASCII file under /ui/judge/
   (the count-in pattern; shipped set = the FIGlet "graffiti" font), with an optional per-combo-tier variant
   for the hit grades. MISS/DROP/FAIL never tier (a miss breaks combo). Pure, no GL; the host draws + scales. */
public final class JudgmentArt {

    private JudgmentArt() {
    }

    // The RESOLVED result is cached, incl. NONE, so a missing file isn't re-probed every frame.
    private static final java.util.Map<String, String[]> CACHE = new java.util.HashMap<>();
    private static final String[] NONE = new String[0];

    // Banner for the engine's lastJudgment ("PERFECT x3" / "MISS" / ...) at the live combo tier, or null when no art exists (caller falls back to plain text).
    public static String[] render(String judgment, int comboMult) {
        if (judgment == null) {
            return null;
        }
        String trimmed = judgment.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String grade = trimmed.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
        boolean tiered = !grade.equals("miss") && !grade.equals("drop") && !grade.equals("fail");
        int tier = tiered ? Math.max(1, comboMult) : 0; // 0 = un-tiered (miss/drop/fail)
        String cacheKey = tier > 0 ? grade + "." + tier : grade;
        String[] art = CACHE.get(cacheKey);
        if (art == null) {
            art = resolve(grade, tier);
            CACHE.put(cacheKey, art);
        }
        return art.length > 0 ? art : null;
    }

    private static String[] resolve(String grade, int tier) {
        if (tier > 0) {
            String[] t = Art.loadArt("/ui/judge/" + grade + "." + tier + ".txt");
            if (t.length > 0) {
                return t;
            }
        }
        String[] base = Art.loadArt("/ui/judge/" + grade + ".txt");
        return base.length > 0 ? base : NONE;
    }
}
