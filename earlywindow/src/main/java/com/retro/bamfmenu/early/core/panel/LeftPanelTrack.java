package com.retro.bamfmenu.early.core.panel;

/* A song's left-panel timeline: one or more animations, each effective from a song-ms onward. active(songMs)
   returns whichever is in effect, so a song can swap animation TYPES partway through ("at certain points of
   a song"). A song that keeps one animation throughout just has a single entry at time 0. The arrays are
   parallel and assumed sorted by start time. */
public final class LeftPanelTrack {

    private final int[] startMs;
    private final LeftPanelAnimation[] anims;

    LeftPanelTrack(int[] startMs, LeftPanelAnimation[] anims) {
        this.startMs = startMs;
        this.anims = anims;
    }

    /* The common case: one animation for the whole song. */
    public static LeftPanelTrack single(LeftPanelAnimation anim) {
        return new LeftPanelTrack(new int[]{0}, new LeftPanelAnimation[]{anim});
    }

    public LeftPanelAnimation active(int songMs) {
        /* null until the first entry's start time, so a track whose first entry begins after 0 shows a blank
           cell until then (the renderer skips a null animation). single() starts at 0, so it always returns
           its one animation; a song with a null leftPanel never reaches here. */
        LeftPanelAnimation cur = null;
        for (int i = 0; i < startMs.length && i < anims.length; i++) {
            if (songMs >= startMs[i]) {
                cur = anims[i];
            } else {
                break;
            }
        }
        return cur;
    }
}
