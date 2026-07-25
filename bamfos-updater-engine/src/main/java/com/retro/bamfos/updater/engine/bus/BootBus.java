package com.retro.bamfos.updater.engine.bus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The one-way feed from the engine to whatever renders the boot (the bus between floors
 * is the filesystem, on purpose -- no classloader assumptions, survives every absent component).
 * Append-only line format, one event per line, tab-separated: EVENT&lt;TAB&gt;field... The earlywindow
 * tails the file and draws; headless runs get only the stdout mirror. Truncated at door start so
 * each boot reads clean. Every write is best-effort: a full disk must never stop a sync.
 */
public final class BootBus {

    public static final String FILE_NAME = "bus.log";

    private final Path file; // null = stdout mirror only (server door, tests)

    private BootBus(Path file) {
        this.file = file;
    }

    /** Client boot bus; truncates the previous boot log and starts fresh. */
    public static BootBus at(Path instanceDir) {
        Path f = instanceDir.resolve(".bamfos-updater").resolve(FILE_NAME);
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, "", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException unwritable) {
            return new BootBus(null); // no bus file; still mirror to stdout
        }
        return new BootBus(f);
    }

    public static BootBus headless() {
        return new BootBus(null);
    }

    /** A quiet-state dmesg line. Mirrored to stdout as the log truth. */
    public void line(String dmesg) {
        System.out.println("[updater] " + dmesg);
        emit("LOG", dmesg);
    }

    /** Starts the POST block when an update is actually running. */
    public void syncBegin(String repo, String from, String to) {
        emit("SYNC", repo, from, to);
    }

    /** Release-notes headline for the status line (PACK UPDATE vX -- ...). */
    public void note(String headline) {
        emit("NOTE", headline);
    }

    public void manifest(int entries) {
        emit("MANIFEST", Integer.toString(entries));
    }

    public void plan(int downloads) {
        emit("PLAN", Integer.toString(downloads));
    }

    public void fileStart(String name) {
        emit("FILE", name);
    }

    public void fileDone(String name) {
        emit("FDONE", name);
    }

    public void applying() {
        emit("PHASE", "Applying");
    }

    /** The finale, owner-authored CAPS: hot result now, pending line only when swaps are staged. */
    public void synced(String from, String to, boolean pending) {
        System.out.println("[updater] BAMFOS UPDATED FROM " + from + " TO " + to + ".");
        emit("SYNCED", from, to);
        if (pending) {
            System.out.println("[updater] BIOS UPDATE COMPLETE. REBOOT TO APPLY.");
            emit("PENDING");
        }
    }

    /** Sync over: ok | skip | fail. The renderer unlocks DEL and reverts the footer on this. */
    public void end(String state) {
        emit("END", state);
    }

    private void emit(String... fields) {
        if (file == null) {
            return;
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                b.append('\t');
            }
            // the format's only reserved chars; none appear in real payloads, strip because a reader
            // must never be able to mis-frame on hostile-ish input
            b.append(fields[i].replace('\t', ' ').replace('\n', ' ').replace('\r', ' '));
        }
        b.append('\n');
        try {
            Files.writeString(file, b.toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }
}
