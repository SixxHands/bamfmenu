package com.retro.bamfmenu.early.core.song;

import com.retro.bamfmenu.early.core.tuning.DetectorConfig;
import com.retro.bamfmenu.early.core.special.SpecialSession;
import com.retro.bamfmenu.early.core.panel.LeftPanelTrack;

import java.util.List;
import java.util.function.Supplier;

/* One song's full definition: identity + audio + per-song auto-charter tuning + its reactive left panel.
   Replaces the old 5-field Song record so a song carries EVERYTHING about itself in one place (see
   SongRegistry, the single file you edit to add a song) instead of being smeared across the provider with
   "recovery".equals(...) branches and global constants.

   leftPanel is a Supplier<LeftPanelTrack> so the panel is PERMISSIVE and pluggable: a song composes its
   track from ANY mix of LeftPanelAnimation types -- the reactive FaceFrameAnimation with its OWN registered
   face frames, the scripted-frame type, an audio visualizer, or any custom type -- and the renderer never
   needs to know which (it just draws what frame(State) returns). null leftPanel => blank left cell. The
   supplier rebuilds the track fresh per play so per-song animation state never leaks across games. */
public record SongDef(
        String id,                          // high-score key + the recovery check; stable, never rename (saves are keyed on it)
        String name,                        // short name in the "Select Song" list
        String label,                       // "name by artist" header drawn during play
        int order,                          // jukebox sort key (ascending); 0 is reserved for Recovery (anchored to SONGS[0])
        String ogg,                         // bundled OGG resource path
        double bpm,                         // grid tempo hint (0 = auto-detect); FAR is 140
        DetectorConfig detector,            // per-song auto-charter tuning (DetectorConfig.defaults() = pre-refactor globals)
        Supplier<LeftPanelTrack> leftPanel, // the reactive left panel, or null for a blank left cell
        Supplier<SpecialSession> special,   // an optional distinct gameplay session ([TAB] in the jukebox), or null for none
        List<SongDifficulty> difficulties   // the DATA-DRIVEN difficulties this song offers (never null; empty = none)
) {
    /* A fresh left-panel track for this song (built per play so animation state does not carry over), or
       null if the song has no custom left cell. */
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

    /* A fresh SPECIAL-mode session for this song, or null if it has no special. */
    public SpecialSession newSpecial() {
        return special != null ? special.get() : null;
    }

    /* This song's resource folder: /songs/<id>/. The self-contained song-folder convention -- a
       song's audio, charts, and panels all live here, SAME shape in-jar and under <gameDir>/BAMFOS/songs/.
       's song.json will resolve all per-song files relative to this; today the explicit ogg field
       already points inside it. */
    public String dir() {
        return "/songs/" + id;
    }
}
