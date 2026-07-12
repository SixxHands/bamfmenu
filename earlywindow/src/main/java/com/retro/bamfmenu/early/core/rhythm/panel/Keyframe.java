package com.retro.bamfmenu.early.core.rhythm.panel;

/* One frame of a left-panel animation clip: the ASCII art to show, how long to hold it (ms), and its ABGR color.
   Shared by ScriptedFrameAnimation (timed clips) and FaceFrameAnimation (each reactive look is now a clip of these,
   so a "look" can be a multi-frame animation rather than a single static frame). Pure data; render-thread only. */
public record Keyframe(String[] art, int durationMs, int color) {
}
