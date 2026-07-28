package com.retro.bamfos.updater.watchdog;

import java.nio.file.Files;
import java.nio.file.Path;

// Sentinel file written by /bamfos stop so the wrapper script exits instead of relaunching the server. Filename must match run-server.bat, run-server.sh, and ServerMain.
final class StopFile {

    static final String NAME = "stop";

    /**
     * Writes the stop sentinel and returns true on success. Callers on a wrapped server must abort the halt
     * on false so the wrapper loop does not immediately relaunch it.
     */
    static boolean write(Path dir) {
        try {
            Path f = dir.resolve(".bamfos-updater").resolve(NAME);
            Files.createDirectories(f.getParent());
            Files.writeString(f, Long.toString(System.currentTimeMillis()));
            return Files.isRegularFile(f); // check explicitly instead of assuming write succeeded
        } catch (Throwable unwritable) {
            return false;
        }
    }

    private StopFile() {
    }
}
