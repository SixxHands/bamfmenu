package com.retro.bamfmenu.early.core.asset;

import java.util.ArrayList;
import java.util.List;

/* A tiny bundled VIDEO clip for the terminal: a sequence of pre-extracted frame images (jpg, from a dev-time ffmpeg
   pass) + a fixed fps, exposed as frame(tSeconds) for a renderer that samples on its own clock. Frames are probed
   SEQUENTIALLY (<base>/f00.jpg, f01.jpg, ... until the first miss) because classpath DIRECTORY enumeration is
   unreliable in the early-window union jar, but probing names is plain getResourceAsStream. The companion audio track
   lives at <base>/audio.ogg by convention (played by the host through the existing OpenAL player, not by this class).
   Headless: no bios/, no GL; decode is AsciiImage.load (JDK ImageIO). */
public final class VideoClip {

    private final AsciiImage.Source[] frames;
    private final double fps;

    private VideoClip(AsciiImage.Source[] frames, double fps) {
        this.frames = frames;
        this.fps = fps;
    }

    /** Load <base>/fNN.jpg sequentially until the first missing index. Null if no frames load (caller falls back). */
    public static VideoClip load(String base, double fps) {
        List<AsciiImage.Source> list = new ArrayList<>();
        for (int i = 0; i < 999; i++) {
            AsciiImage.Source f = AsciiImage.load(String.format("%s/f%02d.jpg", base, i));
            if (f == null) {
                break;
            }
            list.add(f);
        }
        return list.isEmpty() ? null : new VideoClip(list.toArray(new AsciiImage.Source[0]), fps);
    }

    public int frameCount() {
        return frames.length;
    }

    public double durationSeconds() {
        return frames.length / fps;
    }

    /** The frame showing at t seconds (clamped: t<0 -> first, past the end -> last). */
    public AsciiImage.Source frame(double tSeconds) {
        return frames[frameIndex(tSeconds)];
    }

    /** The clamped frame INDEX at t seconds, also the cheap identity key for a renderer's texture upload. */
    public int frameIndex(double tSeconds) {
        int i = (int) (tSeconds * fps);
        return Math.max(0, Math.min(frames.length - 1, i));
    }

    public int frameW() {
        return frames[0].image().getWidth();
    }

    public int frameH() {
        return frames[0].image().getHeight();
    }

    /** A frame's raw ARGB pixels (BufferedImage.getRGB order) for a REAL-bitmap renderer, memoized per frame;
        the full-quality path draws the actual picture, no cell rasterization. ~230KB per 240x240 frame. */
    public int[] frameArgb(int index) {
        int i = Math.max(0, Math.min(frames.length - 1, index));
        if (argbCache == null) {
            argbCache = new int[frames.length][];
        }
        if (argbCache[i] == null) {
            var img = frames[i].image();
            argbCache[i] = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        }
        return argbCache[i];
    }

    private int[][] argbCache;
}
