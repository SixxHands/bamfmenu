package com.retro.bamfmenu.early.core.rhythm;

import com.retro.bamfmenu.early.core.asset.Art;

/* Data-driven judgment "pop" art for the rhythm game (Phase C2). Maps a judgment to a multi-line ASCII banner
   under /ui/judge/ (shipped set = the FIGlet "graffiti" font); the host scales it to fill the judgment grid.

   PER-COMBO-TIER: the hit grades (PERFECT/GOOD/OKAY/BAD) can carry a DIFFERENT banner per combo-multiplier tier
   (1..maxMult, the same x1..x4 tier shown in the HUD) -- `/ui/judge/<grade>.<tier>.txt` (e.g. `perfect.2.txt`).
   It falls back to the un-tiered `/ui/judge/<grade>.txt`, then to plain text, so you only author the tiers you
   want. MISS / DROP / FAIL are EXCLUDED from tiers (a miss breaks combo) -- they always use `/ui/judge/<grade>.txt`.

   Each banner is its own editable file (the count-in pattern): drop in `<grade>.<tier>.txt` to restyle one tier,
   delete it to fall back. Lazy cache per (grade,tier) -- the RESOLVED result (incl. the fallback / "none") is
   cached, so a missing file isn't re-read every frame. Pure -- no GL; the host draws + scales the returned lines. */
public final class JudgmentArt {

    private JudgmentArt() {
    }

    private static final java.util.Map<String, String[]> CACHE = new java.util.HashMap<>();
    private static final String[] NONE = new String[0];

    /* The banner lines for this judgment + current combo multiplier, or null if no art is defined (caller falls
       back to plain text). `judgment` is the engine's lastJudgment ("PERFECT x3" / "MISS" / ...); the grade is
       its first word, lowercased. `comboMult` is the live combo tier (1..maxMult). */
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

    /* Resolve a (grade, tier) to art: the tier-specific file `<grade>.<tier>.txt`, else the un-tiered base file
       `<grade>.txt`, else NONE (caller draws plain text). */
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
