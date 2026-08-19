package com.retro.bamfos.updater.watchdog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/* Kills the JVM if halt(false) hangs on a stuck mod thread during shutdown so the wrapper isn't
 * stuck waiting forever. Uses Runtime.halt instead of System.exit since shutdown hooks are usually what hang. */
final class ShutdownBackstop {

    static final String PREFIX = "shutdown-hang-";

    /** Hands the thread back so a caller can cancel it. Daemon, because this must never be what keeps a JVM
     *  alive. exit is passed in so a test can watch it fire without dying with it. */
    static Thread arm(Path dir, int graceSeconds, Runnable exit, java.util.function.Consumer<String> log) {
        if (graceSeconds <= 0) {
            return null; // disabled (0 means don't force-exit a stuck shutdown)
        }
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(graceSeconds * 1000L);
            } catch (InterruptedException cancelled) {
                return;
            }
            // if we're still running past the deadline, stopServer() is hung
            String where = write(dir, report(Thread.getAllStackTraces(), System.currentTimeMillis()));
            log.accept("shutdown has not finished in " + graceSeconds + "s, taking the exit so the wrapper"
                    + " can relaunch. what was holding it: " + where);
            exit.run();
        }, "bamfos-exit-backstop");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Formats live thread stacks sorted by name/id so dumps are deterministic and easy to test. */
    static String report(Map<Thread, StackTraceElement[]> stacks, long at) {
        StringBuilder sb = new StringBuilder("BAMFOS shutdown hang, ").append(at).append('\n');
        sb.append("the server was asked to stop and did not finish. threads below.\n");
        Map<String, StackTraceElement[]> byName = new TreeMap<>();
        for (Map.Entry<Thread, StackTraceElement[]> e : stacks.entrySet()) {
            // name collisions are real (pools reuse names), so keep the id to tell them apart
            byName.put(e.getKey().getName() + " #" + e.getKey().threadId()
                    + " " + e.getKey().getState(), e.getValue());
        }
        for (Map.Entry<String, StackTraceElement[]> e : byName.entrySet()) {
            sb.append('\n').append(e.getKey()).append('\n');
            for (StackTraceElement f : e.getValue()) {
                sb.append("    at ").append(f).append('\n');
            }
        }
        return sb.toString();
    }

    /** Writes the thread dump and returns its path or error reason. Never throws since we're about to halt. */
    static String write(Path dir, String text) {
        try {
            Path f = dir.resolve(".bamfos-updater").resolve(PREFIX + System.currentTimeMillis() + ".txt");
            Files.createDirectories(f.getParent());
            Files.writeString(f, text, StandardCharsets.UTF_8);
            return f.toString();
        } catch (Throwable unwritable) {
            return "nowhere, the dump could not be written (" + unwritable + ")";
        }
    }

    private ShutdownBackstop() {
    }
}
