package com.retro.bamfmenu.early.core.rhythm.special;

import com.retro.bamfmenu.early.core.audio.AudioPlayer;
import com.retro.bamfmenu.early.core.rhythm.model.SongDef;

/* A song's optional SPECIAL mode: its own gameplay session, hosted on the render thread exactly like the
   rhythm game, reusing the audio engine + the song's presets (SongDef / DetectorConfig). Returning lines
   instead of drawing keeps all GL in the provider, so a session is pure logic with no GL/Minecraft refs,
   safe in the service jar. */
public interface SpecialSession {

    /* Called once on entry, the audio is already playing so its clock/waveform are readable off the AudioPlayer. */
    void init(SongDef song, AudioPlayer audio);

    void step(int songMs);

    /* event = {key, action, nanoTime} from the input queue. ESC is the host's (quit), never watch for it. */
    void handleKey(long[] event);

    /* True = over, the host returns to BIOS. */
    boolean isDone();

    /* The lines to draw this frame, top to bottom at the current cols/rows grid; an empty array draws nothing. */
    String[] render(int cols, int rows);
}
