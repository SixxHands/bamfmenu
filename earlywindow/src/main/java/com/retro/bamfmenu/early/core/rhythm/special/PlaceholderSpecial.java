package com.retro.bamfmenu.early.core.rhythm.special;

import com.retro.bamfmenu.early.core.audio.AudioPlayer;
import com.retro.bamfmenu.early.core.rhythm.model.SongDef;

/* The no-op stand-in for a song's SPECIAL mode: it just shows a "coming soon" card so a song can declare a
   special today and have it LAUNCH (proving the Mode.SPECIAL host + the jukebox launch path) before the real
   per-song gameplay exists. The host handles ESC -> back to BIOS, so this never finishes on its own. The
   real FAR special will replace this with actual gameplay that uses the audio clock + the song's presets. */
public final class PlaceholderSpecial implements SpecialSession {

    private String name = "";

    @Override
    public void init(SongDef song, AudioPlayer audio) {
        this.name = song != null ? song.name() : "";
    }

    @Override
    public void step(int songMs) {
        /* nothing yet, the real session advances its game here */
    }

    @Override
    public void handleKey(long[] event) {
        /* nothing yet, ESC (quit) is handled by the host */
    }

    @Override
    public boolean isDone() {
        return false; // the host exits on ESC
    }

    @Override
    public String[] render(int cols, int rows) {
        return new String[]{
                "SPECIAL MODE",
                "",
                name,
                "",
                "( coming soon )",
                "",
                "[ESC] Back to BIOS",
        };
    }
}
