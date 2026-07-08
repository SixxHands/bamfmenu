package com.retro.bamfmenu.early.bios.transition;

/* A Transition backed by cumulative phase-END times (ms from start) + a wall-clock start. The reusable core
   the cutscene + the lead-in (and future transitions) share: nanoTime->ms, "which phase are we in", and "are
   we past the end". No GL, no audio, no state beyond the start time -- pure timing. */
public abstract class PhaseTimeline implements Transition {

    private final long[] phaseEndsMs; // cumulative phase-end times (ascending); the last entry = total duration
    private long startNanos;
    private boolean started;

    protected PhaseTimeline(long[] phaseEndsMs) {
        this.phaseEndsMs = phaseEndsMs;
    }

    @Override
    public void start(long nowNanos) {
        this.startNanos = nowNanos;
        this.started = true;
    }

    public boolean started() {
        return started;
    }

    @Override
    public long elapsedMs(long nowNanos) {
        return (nowNanos - startNanos) / 1_000_000L;
    }

    @Override
    public int phase(long nowNanos) {
        long e = elapsedMs(nowNanos);
        for (int i = 0; i < phaseEndsMs.length; i++) {
            if (e < phaseEndsMs[i]) {
                return i;
            }
        }
        return phaseEndsMs.length;
    }

    @Override
    public boolean done(long nowNanos) {
        return phaseEndsMs.length == 0 || elapsedMs(nowNanos) >= phaseEndsMs[phaseEndsMs.length - 1];
    }

    /* The cumulative end time (ms) of phase i -- for subclasses that expose named boundaries. */
    protected final long phaseEnd(int i) {
        return phaseEndsMs[i];
    }
}
