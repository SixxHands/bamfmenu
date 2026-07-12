package com.retro.bamfmenu.early.core.rhythm.model;

import com.retro.bamfmenu.early.core.tools.audio.DetectorConfig;
import com.retro.bamfmenu.early.core.rhythm.special.SpecialSession;
import com.retro.bamfmenu.early.core.rhythm.panel.LeftPanelTrack;

import java.util.List;
import java.util.function.Supplier;

/* One song's full definition. Replaces the old 5-field Song record so a song carries EVERYTHING about
   itself in one place (SongRegistry, the single file you edit to add a song) instead of being smeared
   across the provider with "recovery".equals(...) branches and global constants. */
public record SongDef(
        String id,                          // high-score key + the recovery check; stable, never rename (saves are keyed on it)
        String name,                        // short name in the "Select Song" list
        String label,                       // "name by artist" header drawn during play
        int order,                          // jukebox sort key (ascending); 0 is reserved for Recovery (anchored to SONGS[0])
        String ogg,                         // bundled OGG resource path
        double bpm,                         // grid tempo hint (0 = auto-detect)
        DetectorConfig detector,            // per-song auto-charter tuning (DetectorConfig.defaults() = pre-refactor globals)
        Supplier<LeftPanelTrack> leftPanel, // the reactive left panel, or null for a blank left cell
        Supplier<SpecialSession> special,   // an optional distinct gameplay session ([TAB] in the jukebox), or null for none
        List<SongDifficulty> difficulties   // never null; empty = none
) {
    // Built fresh per play so animation state never carries over; null if the song has no left cell.
    public LeftPanelTrack newLeftPanel() {
        return leftPanel != null ? leftPanel.get() : null;
    }

    /* The difficulty with this token (the chart filename / score-key stem), or null if the song doesn't offer it. */
    public SongDifficulty difficulty(String token) {
        if (difficulties != null && token != null) {
            for (SongDifficulty d : difficulties) {
                if (token.equalsIgnoreCase(d.token())) {
                    return d;
                }
            }
        }
        return null;
    }

    // A fresh SPECIAL-mode session per call, or null if the song has no special.
    public SpecialSession newSpecial() {
        return special != null ? special.get() : null;
    }

    // The song's folder; audio, charts, and panels all live here, SAME shape in-jar and under <gameDir>/BAMFOS/songs/.
    public String dir() {
        return "/songs/" + id;
    }
}
