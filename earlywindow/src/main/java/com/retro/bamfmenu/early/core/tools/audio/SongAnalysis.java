package com.retro.bamfmenu.early.core.tools.audio;

import com.retro.bamfmenu.early.core.audio.AudioPlayer;
import com.retro.bamfmenu.early.core.tools.audio.dsp.SpectralAnalyzer;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCStdlib;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/* The song-ANALYSIS tool: decode an OGG and run the full detector chain (SpectralAnalyzer bands + the legacy
   onset/ZCR pass) into an immutable feature bank, the analysis half DETACHED from AudioPlayer (which is now a
   pure playback function; the fused version made one class do two jobs and dragged DetectorConfig into every
   playback call site). Consumers are the charting pipeline (DspCharter via ChartRequest, the genCharts
   bootstrap) and the Recovery cutscene's stab-sync, nothing else; rhythm GAMEPLAY plays committed charts and
   never analyzes. Headless: no OpenAL device, no GL; decode is STB Vorbis, the OGG read reuses
   AudioPlayer.readResource (classpath or "file:" disk songs). Returns null on any failure (callers degrade). */
public final class SongAnalysis {

    private final float[] onsets;
    private final int[] zcr;
    private final int hopMs;
    private final float[] superflux;
    private final float[] centroid;
    private final double superfluxFrameMs;
    private final float[] melodicOdf;
    private final float[] melodicPitch;
    private final float[] melodicF0;
    private final float[] percussionOdf;
    private final float[] cdConf;

    private SongAnalysis(float[] onsets, int[] zcr, int hopMs, float[] superflux, float[] centroid,
            double superfluxFrameMs, float[] melodicOdf, float[] melodicPitch, float[] melodicF0,
            float[] percussionOdf, float[] cdConf) {
        this.onsets = onsets;
        this.zcr = zcr;
        this.hopMs = hopMs;
        this.superflux = superflux;
        this.centroid = centroid;
        this.superfluxFrameMs = superfluxFrameMs;
        this.melodicOdf = melodicOdf;
        this.melodicPitch = melodicPitch;
        this.melodicF0 = melodicF0;
        this.percussionOdf = percussionOdf;
        this.cdConf = cdConf;
    }

    /* Decode + analyze a song resource with the given detector tuning. The SAME chain the fused AudioPlayer ran
       at decode time (verbatim: legacy onset/ZCR pass first, then SpectralAnalyzer), so committed charts stay
       byte-identical. Null on decode/analysis failure. */
    public static SongAnalysis analyze(String resource, DetectorConfig cfg) {
        ByteBuffer ogg = null;
        ShortBuffer pcm = null;
        try {
            ogg = AudioPlayer.readResource(resource);
            int channels;
            int sampleRate;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer ch = stack.mallocInt(1);
                IntBuffer sr = stack.mallocInt(1);
                pcm = STBVorbis.stb_vorbis_decode_memory(ogg, ch, sr);
                if (pcm == null) {
                    return null;
                }
                channels = ch.get(0);
                sampleRate = sr.get(0);
            }
            int frames = pcm.limit() / Math.max(1, channels);
            Onsets legacy = computeOnsets(pcm, channels, frames, sampleRate);
            SpectralAnalyzer.Result sa = SpectralAnalyzer.analyze(pcm, channels, frames, sampleRate, cfg);
            return new SongAnalysis(legacy.flux, legacy.zc, legacy.hopMs,
                    sa.odf(), sa.centroid(), SpectralAnalyzer.HOP * 1000.0 / Math.max(1, sampleRate),
                    sa.melodicOdf(), sa.melodicPitch(), sa.melodicF0(), sa.percussionOdf(), sa.cdConf());
        } catch (Throwable t) {
            System.err.println("[bamfmenu] song analysis failed: " + t);
            return null;
        } finally {
            if (pcm != null) {
                try { LibCStdlib.free(pcm); } catch (Throwable ignored) { }
            }
            if (ogg != null) {
                try { MemoryUtil.memFree(ogg); } catch (Throwable ignored) { }
            }
        }
    }

    /* Per-hop (~10ms) onset strength (normalized positive RMS-energy flux) + zero-crossing rate, the legacy
       fallback charter's inputs. Moved VERBATIM from the fused AudioPlayer (one linear pass, first channel). */
    private record Onsets(float[] flux, int[] zc, int hopMs) { }

    private static Onsets computeOnsets(ShortBuffer pcm, int channels, int frames, int rate) {
        if (frames <= 0 || rate <= 0 || channels <= 0) {
            return new Onsets(new float[0], new int[0], 10);
        }
        int hop = Math.max(1, rate / 100); // ~10ms
        int hops = frames / hop;
        float[] energy = new float[hops];
        int[] zc = new int[hops];
        for (int h = 0; h < hops; h++) {
            int start = h * hop;
            double e = 0;
            int crossings = 0;
            int prevSign = 0;
            for (int i = 0; i < hop; i++) {
                int frame = start + i;
                if (frame >= frames) {
                    break;
                }
                int s = pcm.get(frame * channels);
                e += (double) s * s;
                int sign = Integer.signum(s);
                if (sign != 0) {
                    if (prevSign != 0 && sign != prevSign) {
                        crossings++;
                    }
                    prevSign = sign;
                }
            }
            energy[h] = (float) Math.sqrt(e / hop);
            zc[h] = crossings;
        }
        float[] flux = new float[hops];
        float max = 1e-6f;
        for (int h = 1; h < hops; h++) {
            flux[h] = Math.max(0f, energy[h] - energy[h - 1]);
            if (flux[h] > max) {
                max = flux[h];
            }
        }
        for (int h = 0; h < hops; h++) {
            flux[h] /= max;
        }
        return new Onsets(flux, zc, (int) Math.round(1000.0 * hop / rate));
    }

    public float[] onsets() {
        return onsets;
    }

    public int[] zcr() {
        return zcr;
    }

    public int hopMs() {
        return hopMs;
    }

    /* SuperFlux onset envelope (normalized 0..1, one per 10ms frame). */
    public float[] superflux() {
        return superflux;
    }

    /* Per-frame spectral centroid in Hz (brightness), parallel to superflux(). */
    public float[] centroid() {
        return centroid;
    }

    /* Exact ms between SuperFlux frames, the charter times notes with this (not the integer hopMs). */
    public double superfluxFrameMs() {
        return superfluxFrameMs;
    }

    /* Melodic-band onset envelope (the lead/"piano" line) for the piano-priority charter. */
    public float[] melodicOdf() {
        return melodicOdf;
    }

    /* Per-frame lead pitch in Hz (strongest melodic band), parallel to melodicOdf(); drives lane choice. */
    public float[] melodicPitch() {
        return melodicPitch;
    }

    /* Per-frame HPS fundamental in Hz (parallel to melodicOdf); gates out low-fundamental background onsets. */
    public float[] melodicF0() {
        return melodicF0;
    }

    /* Percussion-band onset envelope (the "marimba"/hats), used only for sparse gap-fill. */
    public float[] percussionOdf() {
        return percussionOdf;
    }

    /* Per-frame complex-domain phase confidence (parallel to melodicOdf); vetoes low-confidence onsets. */
    public float[] cdConf() {
        return cdConf;
    }
}
