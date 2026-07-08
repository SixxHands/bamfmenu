package com.retro.bamfmenu.early.core.song;

/* One difficulty a song offers, DATA-DRIVEN so a song can declare ANY ordered, named set -- not just the fixed
   easy/normal/hard. This is what lets an osu!mania import keep its real difficulties (Beginner / Normal / Hyper /
   Another / Green Another, …) instead of being squeezed into three slots.

   - name     : the menu label shown to the player (e.g. "Hyper").
   - token    : the chart filename stem AND the high-score key suffix (songs/<id>/<token>.chart,
                rhythmHigh.<id>.<token>). Stable; sanitized to a safe file/key string.
   - order    : menu sort order (ascending = easier first, by convention).
   - sensDelta: the auto-charter density shift used only when GENERATING a chart (never at play -- a committed
                chart is read as-is), kept so the default easy/normal/hard still derive their densities.

   A song with no declared list defaults to easy/normal/hard (see SongLoader), so every existing song is
   unchanged. Pure data, no GL/MC. */
public record SongDifficulty(String name, String token, int order, float sensDelta) {

    public SongDifficulty(String name, String token, int order) {
        this(name, token, order, 0f);
    }

    /* SPECIAL is a distinct gameplay session, not a scored chart; reuse the token convention so callers can tell. */
    public boolean isSpecial() {
        return "special".equalsIgnoreCase(token);
    }
}
