package com.retro.bamfmenu.early.core.dsp;

/* A minimal, self-contained radix-2 iterative Cooley-Tukey FFT for the auto-charter's spectral analysis.
   No external DSP library (the early window is pure Java + LWJGL). N must be a power of two; bit-reversal
   indices and twiddle factors are precomputed once and reused for every frame. transform() runs in place on
   parallel real/imag arrays. For our real audio frames we pass the samples in re[] with im[] all zero and
   read magnitudes from the first N/2+1 bins. */
public final class Fft {

    private final int n;
    private final int[] reverse;
    private final float[] cos;
    private final float[] sin;

    Fft(int n) {
        if (n < 2 || (n & (n - 1)) != 0) {
            throw new IllegalArgumentException("Fft size must be a power of two: " + n);
        }
        this.n = n;
        this.reverse = new int[n];
        int bits = Integer.numberOfTrailingZeros(n);
        for (int i = 0; i < n; i++) {
            reverse[i] = Integer.reverse(i) >>> (32 - bits);
        }
        this.cos = new float[n / 2];
        this.sin = new float[n / 2];
        for (int k = 0; k < n / 2; k++) {
            double a = -2.0 * Math.PI * k / n;
            cos[k] = (float) Math.cos(a);
            sin[k] = (float) Math.sin(a);
        }
    }

    int size() {
        return n;
    }

    /* In-place forward FFT on re[]/im[] (each length n). */
    void transform(float[] re, float[] im) {
        for (int i = 0; i < n; i++) {
            int j = reverse[i];
            if (j > i) {
                float tr = re[i]; re[i] = re[j]; re[j] = tr;
                float ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            int half = len >> 1;
            int step = n / len;
            for (int i = 0; i < n; i += len) {
                int k = 0;
                for (int j = i; j < i + half; j++) {
                    float wr = cos[k];
                    float wi = sin[k];
                    float xr = re[j + half];
                    float xi = im[j + half];
                    float tr = xr * wr - xi * wi;
                    float ti = xr * wi + xi * wr;
                    re[j + half] = re[j] - tr;
                    im[j + half] = im[j] - ti;
                    re[j] += tr;
                    im[j] += ti;
                    k += step;
                }
            }
        }
    }
}
