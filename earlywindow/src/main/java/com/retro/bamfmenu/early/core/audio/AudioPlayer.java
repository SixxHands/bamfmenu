package com.retro.bamfmenu.early.core.audio;

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

/* Self-contained audio PLAYBACK for the early window, BEFORE Minecraft loads: decode a bundled OGG with STB
   Vorbis and play it through our OWN OpenAL device/context. We own that device only until the Minecraft
   hand-off, then close() it so MC's own sound init is unaffected. positionSeconds() (OpenAL's AL_SEC_OFFSET)
   is the master clock the rhythm engine syncs notes to. No Minecraft refs, so this stays in the service jar.

   A pure playback FUNCTION by design: the song ANALYSIS (SpectralAnalyzer bands, onset/ZCR, DetectorConfig)
   that used to be fused in here lives in core/tools/audio/SongAnalysis, so plain playback (menu music, the
   wysi clip) carries no detector machinery, and bios never sees a DetectorConfig. waveform() stays here on
   purpose: it is cfg-free presentation data (the rhythm header + editor timeline draw it).

   Everything is best-effort: any failure logs and degrades to "no audio" rather than breaking the boot. */
public final class AudioPlayer {

    private long device;
    private long context;
    private int source;
    private int buffer;
    /* A second source+buffer in the SAME context for a one-shot SFX (e.g. the damage sound) that mixes over
       the song. Kept here, not in a separate AudioPlayer, because each AudioPlayer owns its own OpenAL
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

    /* Decode + start playing a bundled OGG resource (e.g. "/songs/far.ogg"). Returns true if audio is
       playing. Safe to call once; call close() before re-playing. */
    public boolean play(String resource) {
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

    /* Current playback position in seconds, the clock the rhythm engine syncs to. */
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
       continues exactly where it left off, used by the volume menu pause. */
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
       resource. This is the one hook that lets a disk SongDef play exactly like a built-in. PUBLIC: the
       SongAnalysis tool reuses this exact read so analysis sees the same bytes playback does. */
    public static ByteBuffer readResource(String resource) throws Exception {
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
