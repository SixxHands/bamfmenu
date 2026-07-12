package com.retro.bamfmenu.early.core.rhythm.model;

/* The per-song difficulty chosen in the jukebox. Easy/Normal/Hard auto-derive the auto-charter density from
   the song's tuned Normal DetectorConfig: NORMAL = the song's detector as-is; EASY/HARD shift its sensitivity
   by sensDelta (Easy = lower = fewer notes, Hard = higher = more notes). SPECIAL is NOT a charted difficulty
   (it launches the song's SpecialSession instead) and is only offered for songs that define one. token is
   the save-key suffix (rhythmHigh.<id>.<token> / rhythmPerf.<id>.<token>); label is the UI text. Enum order
   is the menu order (EASY, NORMAL, HARD, then SPECIAL when present). */
public enum Difficulty {
    EASY("Easy", "easy", -0.12f),
    NORMAL("Normal", "normal", 0f),
    HARD("Hard", "hard", 0.08f),
    SPECIAL("Special", "special", 0f);

    public final String label;
    public final String token;
    public final float sensDelta; // added to the song's Normal sensitivity to derive this difficulty's density

    Difficulty(String label, String token, float sensDelta) {
        this.label = label;
        this.token = token;
        this.sensDelta = sensDelta;
    }

    /* SPECIAL launches a SpecialSession; every other difficulty is a scored rhythm chart. */
    public boolean scored() {
        return this != SPECIAL;
    }
}
