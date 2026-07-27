package com.retro.bamfos.updater.engine.bus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

// One-way tab-separated event log (.bamfos-updater/bus.log) tailed by earlywindow to render boot sync status.
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

    /** Appends to the existing bus file instead of truncating it. Truncating drops the file size below
     *  UpdaterFeed's live read offset, making the in-session tailer stop reading progress permanently. */
    public static BootBus append(Path instanceDir) {
        Path f = instanceDir.resolve(".bamfos-updater").resolve(FILE_NAME);
        try {
            Files.createDirectories(f.getParent());
            if (!Files.exists(f)) {
                Files.writeString(f, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE);
            }
        } catch (IOException unwritable) {
            return new BootBus(null);
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

    /** What the hardware block's three updater rows report: the channel a sync would use, the
     *  engine build that would run it, and the tag pin. Sent once, right off the config, so the rows carry
     *  real values on a boot that syncs nothing, which is nearly every boot. No stdout mirror, because the log
     *  already carries all three (EngineEntry prints the build, the door prints the pin, and the repo is in
     *  server-sync.log) and this event exists only to fill a display row. */
    public void ident(String repo, String engineVersion, String tagPin) {
        emit("IDENT", repo, engineVersion, tagPin);
    }

    /** Starts the POST block when an update is actually running. */
    public void syncBegin(String repo, String from, String to) {
        emit("SYNC", repo, from, to);
    }

    /** Release-notes headline shown on the status line (e.g. PACK UPDATE vX). */
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

    /** Sync over: ok | skip | limited | no-release | fail. The renderer unlocks DEL and reverts the footer on
     *  this, only `fail` earns an abort why-line (the other four already said their piece), and anything but
     *  `ok` gets a closing row so the POST block terminates instead of spinning for the rest of the boot.
     *  `limited` and `no-release` exist for exactly that reason: both are the WORLD refusing, not this box
     *  breaking, and either one reported as `fail` stacks "sync aborted" under a line that already said the
     *  honest thing.
     *  The reader is UpdaterFeed.event's END arm, in the earlywindow module, and it matches these strings
     *  by literal. Named here on purpose: renaming a state on one side of a filesystem bus compiles fine
     *  and just renders two rows contradicting each other, so this is the search term that finds both
     *  halves. */
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
