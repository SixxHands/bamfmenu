package com.retro.bamfos.updater.boot;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/* Server entry point: java -jar bamfos-updater-boot.jar --server --dir . [--tag v] [--repair] [--panel].
 * Runs before the Minecraft JVM starts. Always exits 0 so a broken updater never blocks the server from
 * booting. --panel skips writing the wrapper mark (for hosts like Pterodactyl that manage restarts). */
public final class ServerMain {

    public static void main(String[] args) {
        try {
            Path dir = Paths.get(".");
            for (int i = 0; i < args.length - 1; i++) {
                if (args[i].equals("--dir")) {
                    dir = Paths.get(args[i + 1]);
                }
            }
            if (panelMode(args)) {
                // log this so operators know auto-restart is off without guessing from silence
                System.out.println("[updater] panel mode: something else owns restarts, so the watchdog will "
                        + "announce an update and wait for you instead of stopping the server for it");
                clearWrapperMark(dir);
            } else {
                markPreJvmRun(dir);
            }
            clearStaleStop(dir);
            Map<?, ?> answer = CoreSlot.run(dir, args);
            System.exit(answer.get("exit") instanceof Integer code ? code : 0);
        } catch (Throwable t) {
            System.err.println("[updater] sync skipped: " + t);
            System.exit(0); // the updater must never be the reason the server won't boot
        }
    }

    /** Marker file name checked by the watchdog. Keep in sync with WatchdogState.wrapperRan. */
    static final String WRAPPER_MARK = "wrapper-run";

    /** Accepts the flag anywhere in args so startup commands pasted in any order still work. */
    static boolean panelMode(String[] args) {
        for (String a : args) {
            if (a.equals("--panel")) {
                return true;
            }
        }
        return false;
    }

    // Writes a timestamp proving a wrapper ran before the JVM, so the watchdog knows a restart loop will bring the server back.
    static void markPreJvmRun(Path dir) {
        try {
            Path f = dir.resolve(".bamfos-updater").resolve(WRAPPER_MARK);
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Files.writeString(f, Long.toString(System.currentTimeMillis()));
        } catch (Throwable unwritable) {
            // if disk is read-only or full, fail safe to announce-only rather than blocking boot
        }
    }

    /**
     * Clears any leftover wrapper mark so switching from run-server.bat to panel mode
     * doesn't let the watchdog halt a server with no wrapper loop to relaunch it. Never fatal.
     */
    static void clearWrapperMark(Path dir) {
        try {
            java.nio.file.Files.deleteIfExists(dir.resolve(".bamfos-updater").resolve(WRAPPER_MARK));
        } catch (Throwable stuck) {
        }
    }

    /** The name /bamfos stop writes and the wrapper reads. Spelled here, in the watchdog's StopFile, and in
     *  both wrapper scripts, because none of those four link to each other. */
    static final String STOP_FILE = "stop";

    // Clears any leftover stop sentinel from a crashed or killed wrapper cycle so the next manual start doesn't exit right away.
    static void clearStaleStop(Path dir) {
        try {
            java.nio.file.Files.deleteIfExists(dir.resolve(".bamfos-updater").resolve(STOP_FILE));
        } catch (Throwable stuck) {
        }
    }

    private ServerMain() {
    }
}
