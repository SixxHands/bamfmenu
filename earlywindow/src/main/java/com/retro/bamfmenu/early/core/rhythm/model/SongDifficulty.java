package com.retro.bamfmenu.early.core.rhythm.model;

/* One difficulty a song offers, data-driven so an osu!mania import keeps its real difficulty names instead
   of being squeezed into easy/normal/hard. token is the chart filename stem AND the high-score key suffix,
   so keep it stable; sensDelta feeds the auto-charter only when GENERATING, a committed chart plays as-is. */
public record SongDifficulty(String name, String token, int order, float sensDelta) {

    public SongDifficulty(String name, String token, int order) {
        this(name, token, order, 0f);
    }

    // SPECIAL is a distinct gameplay session, not a scored chart; reusing the token is how callers tell.
    public boolean isSpecial() {
        return "special".equalsIgnoreCase(token);
    }
}
