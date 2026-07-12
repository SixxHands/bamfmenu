package com.retro.bamfmenu.early.core.tools.gen;

/* A tiny, deterministic 64-bit xorshift PRNG for the generative song ("A Song Is Born"). Rolled by hand (not
   java.util.Random) so the same seed yields byte-identical output across JVMs, and so each voice/layer can take
   its OWN independent sub-stream via spawn(): the audio, the chart, and the visual all derive from one seed, so
   they are inherently correlated and fully reproducible. Pure logic, no GL/MC, headless core. */
public final class Xorshift64 {

    private long s;

    public Xorshift64(long seed) {
        this.s = seed != 0L ? seed : 0x9E3779B97F4A7C15L; // a zero state is a fixed point for xorshift; avoid it
    }

    /* The raw 64-bit step (Marsaglia xorshift, shifts 13/7/17). */
    public long nextLong() {
        long x = s;
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        s = x;
        return x;
    }

    /* A non-negative int in [0, bound) (bound > 0), from the high bits. */
    public int nextInt(int bound) {
        if (bound <= 0) {
            return 0;
        }
        return (int) ((nextLong() >>> 33) % bound);
    }

    /* A float in [0, 1). */
    public float nextFloat() {
        return (nextLong() >>> 40) * (1.0f / (1 << 24));
    }

    /* An independent sub-stream keyed by id (e.g. a voice index), mixed off the current state so layers do not
       perturb each other's choices. */
    public Xorshift64 spawn(long id) {
        return new Xorshift64(s * 0x9E3779B97F4A7C15L + id);
    }
}
