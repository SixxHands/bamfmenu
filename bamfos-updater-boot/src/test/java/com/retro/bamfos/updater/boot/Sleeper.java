package com.retro.bamfos.updater.boot;

/**
 * Minimal long-lived JVM spawned by CoreSlotTest so double-launch tests have an actual live pid
 * and OS start time (writerStillRunning excludes our own pid).
 */
public final class Sleeper {

    public static void main(String[] args) throws InterruptedException {
        Thread.sleep(600_000); // ten minutes, and the test kills it in a finally long before that
    }

    private Sleeper() {
    }
}
