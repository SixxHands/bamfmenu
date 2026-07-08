package com.retro.bamfmenu.early.core.audio;

import com.retro.bamfmenu.early.core.tuning.DetectorConfig;
import com.retro.bamfmenu.early.core.dsp.SpectralAnalyzer;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCStdlib;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.AL11.AL_SEC_OFFSET; // OpenAL 1.1 source playback-position query
import static org.lwjgl.openal.ALC10.*;

/* Self-contained audio for the early window, BEFORE Minecraft loads: decode a bundled OGG with STB Vorbis
   and play it through our OWN OpenAL device/context. We own that device only until the Minecraft hand-off,
   then close() it so MC's own sound init is unaffected. positionSeconds() (OpenAL's AL_SEC_OFFSET) is the
   master clock the rhythm engine will sync notes to. No Minecraft refs -- this stays in the service jar.

   SPIKE: the whole point is to prove this runs pre-MC and tears down cleanly. Everything is
   best-effort: any failure logs and degrades to "no audio" rather than breaking the boot. */
public final class AudioPlayer {

    private long device;
    private long context;
    private int source;
    private int buffer;
    /* A second source+buffer in the SAME context for a one-shot SFX (e.g. the damage sound) that mixes over
       the song. Kept here -- not a separate AudioPlayer -- because each AudioPlayer owns its own OpenAL
       context, and two contexts would fight over which one is current. */
    private int sfxSource;
    private int sfxBuffer;
    private boolean sfxReady;
    private int sampleRate;
    private float duration;
    private volatile boolean ready;
    private String status = "idle";

    /* A coarse amplitude profile of the whole song (0..1 per bucket), computed once at decode time for the
       header waveform display. */
    private static final int WAVE_BUCKETS = 512;
    private volatile float[] waveform = new float[0];

    /* Onset analysis for auto-charting (computed at decode, before the PCM is freed): per-hop onset
       strength (normalized positive energy flux) + per-hop zero-crossing rate (a cheap brightness proxy
       for lane assignment), with the hop length in ms. ChartGenerator peak-picks these into notes. */
    private volatile float[] onsets = new float[0];
    private volatile int[] zcr = new int[0];
    private volatile int hopMs = 10;

    /* SuperFlux onset envelope + per-frame spectral centroid (Hz) from SpectralAnalyzer, computed at decode.
       This is the new auto-charter front-end; onsets/zcr above stay as the legacy fallback. Same 10ms hop. */
    private volatile float[] superflux = new float[0];
    private volatile float[] centroid = new float[0];
    /* Melodic ("piano") onset envelope + per-frame lead pitch (Hz), and the percussion ("marimba") envelope,
       for the piano-priority charter: detect on the melodic band, lane by melodic pitch, percussion fills
       only big gaps. */
    private volatile float[] melodicOdf = new float[0];
    private volatile float[] melodicPitch = new float[0];
    private volatile float[] melodicF0 = new float[0]; // per-frame HPS fundamental (Hz), for the low-pitch onset gate
    private volatile float[] percussionOdf = new float[0];
    private volatile float[] cdConf = new float[0]; // complex-domain phase confidence (per frame) for the onset veto
    /* Exact ms between SuperFlux frames (HOP/sampleRate); used for note timing so there is no rounding drift
       at non-44.1kHz sample rates (the legacy integer hopMs would diverge from the real frame grid). */
    private volatile double superfluxFrameMs = 10.0;

    /* Decode + run the SAME band analysis as play(), but WITHOUT opening OpenAL or starting playback -- so the
       auto-charter's inputs (melodicOdf/pitch/F0, percussionOdf, cdConf, superflux/centroid, legacy onsets/zcr,
       frameMs) are populated identically to a real play(), headless. This is the Phase-4 chart-bootstrap path:
       a generator can analyze a song and run DspCharter to emit a committed .chart that matches what the live
       game would have charted, with no audio device. Returns true on success; the instance is NOT 'ready' for
       playback afterwards (no source/buffer), only for reading the analysis getters. */
    public boolean analyzeOnly(String resource, DetectorConfig cfg) {
        ByteBuffer ogg = null;
        ShortBuffer pcm = null;
        try {
            ogg = readResource(resource);
            int channels;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer ch = stack.mallocInt(1);
                IntBuffer sr = stack.mallocInt(1);
                pcm = STBVorbis.stb_vorbis_decode_memory(ogg, ch, sr);
                if (pcm == null) {
                    status = "decode failed";
                    return false;
                }
                channels = ch.get(0);
                sampleRate = sr.get(0);
            }
            int frames = pcm.limit() / Math.max(1, channels);
            duration = sampleRate > 0 ? (float) frames / sampleRate : 0f;
            waveform = computeWaveform(pcm, channels, frames, WAVE_BUCKETS);
            computeOnsets(pcm, channels, frames, sampleRate); // legacy onset/zcr (fallback charter)
            SpectralAnalyzer.Result sa = SpectralAnalyzer.analyze(pcm, channels, frames, sampleRate, cfg);
            superflux = sa.odf();
            centroid = sa.centroid();
            melodicOdf = sa.melodicOdf();
            melodicPitch = sa.melodicPitch();
            melodicF0 = sa.melodicF0();
            percussionOdf = sa.percussionOdf();
            cdConf = sa.cdConf();
            superfluxFrameMs = SpectralAnalyzer.HOP * 1000.0 / Math.max(1, sampleRate);
            status = "analyzed (" + channels + "ch " + sampleRate + "Hz " + fmt(duration) + "s)";
            return true;
        } catch (Throwable t) {
            status = "analyze error: " + t;
            System.err.println("[bamfmenu] audio analyze failed: " + t);
            return false;
        } finally {
            if (pcm != null) {
                try { LibCStdlib.free(pcm); } catch (Throwable ignored) { }
            }
            if (ogg != null) {
                try { MemoryUtil.memFree(ogg); } catch (Throwable ignored) { }
            }
        }
    }

    /* Decode + start playing a bundled OGG resource (e.g. "/songs/far.ogg"). Returns true if audio is
       playing. Safe to call once; call close() before re-playing. The DetectorConfig drives the decode-time
       band analysis (SpectralAnalyzer) so each song's chart is detected with its own band settings. */
    public boolean play(String resource, DetectorConfig cfg) {
        ByteBuffer ogg = null;
        ShortBuffer pcm = null;
        try {
            ogg = readResource(resource);

            int channels;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer ch = stack.mallocInt(1);
                IntBuffer sr = stack.mallocInt(1);
                pcm = STBVorbis.stb_vorbis_decode_memory(ogg, ch, sr);
                if (pcm == null) {
                    status = "decode failed";
                    return false;
                }
                channels = ch.get(0);
                sampleRate = sr.get(0);
            }

            device = alcOpenDevice((ByteBuffer) null);
            if (device == 0L) {
                status = "no OpenAL device";
                return false;
            }
            ALCCapabilities alcCaps = ALC.createCapabilities(device);
            context = alcCreateContext(device, (IntBuffer) null);
            if (context == 0L || !alcMakeContextCurrent(context)) {
                status = "no OpenAL context";
                return false;
            }
            AL.createCapabilities(alcCaps);

            buffer = alGenBuffers();
            int format = channels > 1 ? AL_FORMAT_STEREO16 : AL_FORMAT_MONO16;
            alBufferData(buffer, format, pcm, sampleRate);
            int frames = pcm.limit() / Math.max(1, channels);
            duration = sampleRate > 0 ? (float) frames / sampleRate : 0f;
            waveform = computeWaveform(pcm, channels, frames, WAVE_BUCKETS); // before pcm is freed below
            computeOnsets(pcm, channels, frames, sampleRate); // legacy onset/zcr (fallback charter)
            SpectralAnalyzer.Result sa = SpectralAnalyzer.analyze(pcm, channels, frames, sampleRate, cfg);
            superflux = sa.odf();
            centroid = sa.centroid();
            melodicOdf = sa.melodicOdf();
            melodicPitch = sa.melodicPitch();
            melodicF0 = sa.melodicF0();
            percussionOdf = sa.percussionOdf();
            cdConf = sa.cdConf();
            superfluxFrameMs = SpectralAnalyzer.HOP * 1000.0 / Math.max(1, sampleRate);

            source = alGenSources();
            alSourcei(source, AL_BUFFER, buffer);
            alSourcePlay(source);

            ready = true;
            status = "playing (OpenAL " + channels + "ch " + sampleRate + "Hz " + fmt(duration) + "s)";
            return true;
        } catch (Throwable t) {
            status = "error: " + t;
            System.err.println("[bamfmenu] audio play failed: " + t);
            close();
            return false;
        } finally {
            /* alBufferData copied the PCM into the AL buffer, so the decode buffers can go now. */
            if (pcm != null) {
                try { LibCStdlib.free(pcm); } catch (Throwable ignored) { }
            }
            if (ogg != null) {
                try { MemoryUtil.memFree(ogg); } catch (Throwable ignored) { }
            }
        }
    }

    /* Current playback position in seconds -- the clock the rhythm engine syncs to. */
    public float positionSeconds() {
        if (!ready) {
            return 0f;
        }
        try {
            return alGetSourcef(source, AL_SEC_OFFSET);
        } catch (Throwable t) {
            return 0f;
        }
    }

    public float durationSeconds() {
        return duration;
    }

    /* The song's amplitude profile (0..1 per bucket) for the header waveform. */
    public float[] waveform() {
        return waveform;
    }

    public float[] onsets() {
        return onsets;
    }

    public int[] zcr() {
        return zcr;
    }

    /* SuperFlux onset envelope (normalized 0..1, one per 10ms frame) for the auto-charter. */
    public float[] superflux() {
        return superflux;
    }

    /* Per-frame spectral centroid in Hz (brightness), parallel to superflux(); a cheap pitch proxy. */
    public float[] centroid() {
        return centroid;
    }

    /* Exact ms between SuperFlux frames -- the auto-charter times notes with this (not the integer hopMs). */
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

    /* Per-frame HPS fundamental in Hz (parallel to melodicOdf); the charter gates out onsets whose
       fundamental is below a per-song floor (a bit-crushed bass/background, vs the higher melody). */
    public float[] melodicF0() {
        return melodicF0;
    }

    /* Percussion-band onset envelope (the "marimba"/hats), used only for sparse gap-fill. */
    public float[] percussionOdf() {
        return percussionOdf;
    }

    /* Per-frame complex-domain phase confidence (parallel to melodicOdf); the charter vetoes low-confidence
       onsets (steady tones / reverb / drone) that magnitude flux cannot distinguish from real attacks. */
    public float[] cdConf() {
        return cdConf;
    }

    public int hopMs() {
        return hopMs;
    }

    /* Per-hop (~10ms) onset strength (normalized positive RMS-energy flux) + zero-crossing rate, for
       auto-charting. One linear pass over the first channel; cheap (a few million samples, once). */
    private void computeOnsets(ShortBuffer pcm, int channels, int frames, int rate) {
        if (frames <= 0 || rate <= 0 || channels <= 0) {
            onsets = new float[0];
            zcr = new int[0];
            hopMs = 10;
            return;
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
        onsets = flux;
        zcr = zc;
        hopMs = (int) Math.round(1000.0 * hop / rate);
    }

    /* Downsample the decoded PCM to a few hundred peak-amplitude buckets (first channel), normalized. */
    private static float[] computeWaveform(ShortBuffer pcm, int channels, int frames, int buckets) {
        float[] w = new float[buckets];
        if (frames <= 0 || channels <= 0) {
            return w;
        }
        int per = Math.max(1, frames / buckets);
        int globalMax = 1;
        for (int b = 0; b < buckets; b++) {
            int start = b * per;
            int peak = 0;
            for (int i = 0; i < per; i++) {
                int frame = start + i;
                if (frame >= frames) {
                    break;
                }
                int a = Math.abs(pcm.get(frame * channels));
                if (a > peak) {
                    peak = a;
                }
            }
            w[b] = peak;
            if (peak > globalMax) {
                globalMax = peak;
            }
        }
        for (int b = 0; b < buckets; b++) {
            w[b] = w[b] / globalMax;
        }
        return w;
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isPlaying() {
        if (!ready) {
            return false;
        }
        try {
            return alGetSourcei(source, AL_SOURCE_STATE) == AL_PLAYING;
        } catch (Throwable t) {
            return false;
        }
    }

    public void setVolume(float gain) {
        if (ready) {
            try { alSourcef(source, AL_GAIN, Math.max(0f, gain)); } catch (Throwable ignored) { }
        }
    }

    /* Load a one-shot SFX into a SECOND source within THIS player's existing context so it MIXES over the
       song. Call after a successful play() (our context is current only once the song is playing); decodes
       the OGG once, then playSfx() just retriggers it. Best-effort: failure degrades to no SFX. */
    public boolean loadSfx(String resource) {
        if (!ready) {
            return false;
        }
        ByteBuffer ogg = null;
        ShortBuffer pcm = null;
        try {
            ogg = readResource(resource);
            int channels;
            int srate;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer ch = stack.mallocInt(1);
                IntBuffer sr = stack.mallocInt(1);
                pcm = STBVorbis.stb_vorbis_decode_memory(ogg, ch, sr);
                if (pcm == null) {
                    return false;
                }
                channels = ch.get(0);
                srate = sr.get(0);
            }
            sfxBuffer = alGenBuffers();
            int format = channels > 1 ? AL_FORMAT_STEREO16 : AL_FORMAT_MONO16;
            alBufferData(sfxBuffer, format, pcm, srate);
            sfxSource = alGenSources();
            alSourcei(sfxSource, AL_BUFFER, sfxBuffer);
            sfxReady = true;
            return true;
        } catch (Throwable t) {
            System.err.println("[bamfmenu] sfx load failed: " + t);
            return false;
        } finally {
            if (pcm != null) {
                try { LibCStdlib.free(pcm); } catch (Throwable ignored) { }
            }
            if (ogg != null) {
                try { MemoryUtil.memFree(ogg); } catch (Throwable ignored) { }
            }
        }
    }

    /* Fire the loaded SFX from the start at the given gain, mixed over the song. Rewinds first so rapid
       retriggers (back-to-back misses) restart cleanly. Render thread (the thread that owns the context). */
    public void playSfx(float gain) {
        if (!sfxReady) {
            return;
        }
        try {
            alSourcef(sfxSource, AL_GAIN, Math.max(0f, gain));
            alSourceRewind(sfxSource);
            alSourcePlay(sfxSource);
        } catch (Throwable ignored) { }
    }

    /* Stop playback but keep the device/buffers (close() does the full teardown). */
    public void stop() {
        if (ready) {
            try { alSourceStop(source); } catch (Throwable ignored) { }
        }
    }

    /* Pause/resume preserve the playback position (AL_SEC_OFFSET), so the rhythm clock freezes and
       continues exactly where it left off -- used by the volume menu pause. */
    public void pause() {
        if (ready) {
            try { alSourcePause(source); } catch (Throwable ignored) { }
        }
    }

    public void resume() {
        if (ready) {
            try {
                /* Only resume a genuinely PAUSED source: alSourcePlay on a STOPPED/finished source would
                   rewind it to 0 and replay (e.g. reviving the music after a game-over stop()). */
                if (alGetSourcei(source, AL_SOURCE_STATE) == AL_PAUSED) {
                    alSourcePlay(source);
                }
            } catch (Throwable ignored) { }
        }
    }

    /* Seek the playback position (seconds) for chart editor auditioning. AL_SEC_OFFSET is writable on our
       non-streaming full-song buffer, so this is instant + exact. */
    public void seekSeconds(float sec) {
        if (ready) {
            try { alSourcef(source, AL_SEC_OFFSET, Math.max(0f, sec)); } catch (Throwable ignored) { }
        }
    }

    /* Play FROM a given position (seconds): seek THEN start. Use this (not resume(), which only un-pauses) so
       the editor's "play from the cursor" works from a paused OR stopped source. */
    public void playFrom(float sec) {
        if (ready) {
            try {
                alSourcef(source, AL_SEC_OFFSET, Math.max(0f, sec));
                alSourcePlay(source);
            } catch (Throwable ignored) { }
        }
    }

    public String status() {
        return status;
    }

    /* Tear everything down. Called before the Minecraft hand-off so MC's OpenAL init is unaffected. */
    public void close() {
        ready = false;
        sfxReady = false;
        try { if (sfxSource != 0) { alSourceStop(sfxSource); alDeleteSources(sfxSource); sfxSource = 0; } } catch (Throwable ignored) { }
        try { if (sfxBuffer != 0) { alDeleteBuffers(sfxBuffer); sfxBuffer = 0; } } catch (Throwable ignored) { }
        try { if (source != 0) { alSourceStop(source); alDeleteSources(source); source = 0; } } catch (Throwable ignored) { }
        try { if (buffer != 0) { alDeleteBuffers(buffer); buffer = 0; } } catch (Throwable ignored) { }
        try { alcMakeContextCurrent(0L); } catch (Throwable ignored) { }
        try { if (context != 0L) { alcDestroyContext(context); context = 0L; } } catch (Throwable ignored) { }
        try { if (device != 0L) { alcCloseDevice(device); device = 0L; } } catch (Throwable ignored) { }
        status = "closed";
    }

    private static String fmt(float s) {
        int t = (int) (s * 100);
        return (t / 100) + "." + String.format("%02d", t % 100);
    }

    /* Read an OGG into native memory. A "file:"-prefixed resource is read OFF-DISK (user songs + forks under
       <gameDir>/BAMFOS/songs/, whose audio is not on the classpath); anything else is a bundled classpath
       resource. This is the one hook that lets a disk SongDef play exactly like a built-in. */
    private static ByteBuffer readResource(String resource) throws Exception {
        byte[] bytes;
        if (resource != null && resource.startsWith("file:")) {
            bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(resource.substring("file:".length())));
        } else {
            try (InputStream in = AudioPlayer.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("missing audio resource " + resource);
                }
                bytes = in.readAllBytes();
            }
        }
        ByteBuffer buf = MemoryUtil.memAlloc(bytes.length);
        buf.put(bytes);
        buf.flip();
        return buf;
    }
}
