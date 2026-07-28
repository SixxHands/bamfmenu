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
    private int lastPct = -1; // Throttle for fileProgress, reset on fileDone so each file gets a fresh bar.

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

    /** Emits the configured repo name for the Update Channel row before any lock or network call.
     *  Not mirrored to stdout since this only fills the display row; channel() sets the status later. */
    public void ident(String repo) {
        emit("IDENT", repo);
    }

    /** Sets the Update Channel indicator: ok | fail | idle | no-carrier | pinned (one per boot, from finally).
     *  `ok` includes throttles, `idle` means converged with no request, and `pinned` overrides ok/idle.
     *  Matched by literal in UpdaterFeed.indicator (earlywindow); keep both sides in sync. */
    public void channel(String state) {
        emit("CHANNEL", state);
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
        lastPct = -1; // reset progress state so the next file starts a fresh bar
        emit("FDONE", name);
    }

    /** Reports byte progress for the active transfer using the actual download length (not the manifest entry
     *  size, since a release-zip entry downloads the entire archive on its first file). Throttled to one line
     *  per whole percent to avoid flooding the bus file; backwards percentages (new file or retry) still emit. */
    public void fileProgress(String name, long done, long total) {
        if (total <= 0) {
            return; // skip the progress bar if we don't have a Content-Length or manifest size
        }
        int pct = (int) Math.min(100, Math.max(0, done * 100 / total));
        if (pct == lastPct) {
            return;
        }
        lastPct = pct;
        emit("FPROG", name, Long.toString(done), Long.toString(total));
    }

    public void applying() {
        emit("PHASE", "Applying");
    }

    /**
     * The finale, owner-authored CAPS: hot result now, pending line only when swaps are staged.
     * NO stdout mirror, unlike every other line on this bus, and that is the fix and not an oversight: the
     * dedicated server runs this exact code from the wrapper's pre-JVM shim, so it printed
     * BIOS UPDATE COMPLETE. REBOOT TO APPLY. into the wrapper console and logs/latest.log, on a box
     * with no BIOS, at an operator who is not the one who reboots. Each side words its own console: the
     * server has SyncCore.serverLog, the client prints the neutral summary, and the CAPS belong to the one
     * surface that has a boot terminal to draw them on (UpdaterFeed.postBlock, which words these events).
     */
    public void synced(String from, String to, boolean pending) {
        emit("SYNCED", from, to);
        if (pending) {
            emit("PENDING");
        }
    }

    /** Staged updater-only swap where no pack files changed, so we just show REBOOT TO APPLY
     *  (decided by SyncCore). */
    public void pendingOnly() {
        emit("PENDING");
    }

    /** Ends sync: ok | skip | limited | no-release | offline | fail. Unlocks DEL and closes the POST block.
     *  Only `fail` adds a generic abort line; the other non-ok states already logged a specific reason.
     *  Matched by literal in UpdaterFeed.event's END arm (earlywindow); keep both sides in sync. */
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
