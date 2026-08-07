package com.retro.bamfos.updater.engine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Test fixture packaged into a jar by CoreEntryTest under the EngineEntry class name.
 * Loaded through a platform-parented classloader, it writes to disk whether the crash marker
 * was already cleared before startup.
 */
public final class EngineEntry {

    /** Unique exit code so tests can tell this fixture actually ran instead of returning 0. */
    public static final int PROBE_EXIT = 7;

    /** Passed-through args from the stub so the dir flag points at the instance root. */
    public static int enter(String[] args) {
        Path dir = Paths.get(".");
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--dir")) {
                dir = Paths.get(args[i + 1]);
            }
        }
        try {
            Path mark = dir.resolve(".bamfos-updater").resolve("core").resolve("core-unproven");
            Files.writeString(dir.resolve("engine-saw-the-mark.txt"), Boolean.toString(Files.exists(mark)));
        } catch (Exception unwritable) {
            return 1; // treat a missing probe file as a failure
        }
        return PROBE_EXIT;
    }

    private EngineEntry() {
    }
}
