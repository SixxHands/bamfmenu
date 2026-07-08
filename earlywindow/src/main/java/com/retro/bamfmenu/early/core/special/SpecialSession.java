package com.retro.bamfmenu.early.core.special;

import com.retro.bamfmenu.early.core.audio.AudioPlayer;
import com.retro.bamfmenu.early.core.song.SongDef;

/* A song's optional SPECIAL mode: a distinct gameplay session, separate from the normal rhythm game, that
   reuses the audio engine + the song's presets (its SongDef / DetectorConfig). Hosted on the render thread
   exactly like the rhythm game: the provider feeds it the current song time + queued key events, draws what
   render() returns, and exits when isDone() (or on ESC). Returning lines (rather than drawing here) keeps all
   GL in the provider, so a session is pure logic with no GL/Minecraft refs -- safe in the service jar.

   This is the SEAM only; the real per-song gameplay (FAR first) fills it in later. PlaceholderSpecial is the
   no-op stand-in so a song can declare a special today and have it launch a "coming soon" screen. */
public interface SpecialSession {

    /* Called once on entry (render thread). The song's audio is already playing, so the session can read its
       clock (positionSeconds) and decode-time analysis (melodicOdf, etc.) off the AudioPlayer if it wants. */
    void init(SongDef song, AudioPlayer audio);

    /* Advance one frame to the current song time (ms). Render thread. */
    void step(int songMs);

    /* A timestamped key event {key, action, nanoTime} from the input queue (render thread). ESC is handled by
       the host (quit), so a session never needs to watch for it. */
    void handleKey(long[] event);

    /* True when the session is over and the host should return to BIOS. */
    boolean isDone();

    /* The lines to draw this frame, top to bottom; the host draws them (keeping GL in the provider). cols/rows
       are the current terminal grid size so a session can lay itself out. An empty array draws nothing. */
    String[] render(int cols, int rows);
}
