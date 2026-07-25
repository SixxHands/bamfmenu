package com.retro.bamfmenu.early.core.updater;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Tails the updater engine's bus file (.bamfos-updater/bus.log) and turns its events into the POST
 * display model. The filesystem IS the bus on purpose: we never touch updater classes, so any
 * combination of absent/old/broken updater leaves the terminal fully functional. We truncate the
 * file at construction -- the window starts BEFORE the locator phase, so everything read afterwards
 * is THIS boot's; stale last-boot content can never render.
 * Single-consumer by design: poll() and every getter run on the render thread only.
 */
public final class UpdaterFeed {

    /** One rendered row of the updater POST block; bright marks the pack's CAPS finale lines. */
    public record Line(String text, boolean bright) {
    }

    private static final char[] SPINNER = {'|', '/', '-', '\\'};
    /* the block must NOT keep growing downward -- it
       holds a fixed footprint and SCROLLS ITSELF, each line past the max shifting everything up
       one, like a real terminal region. The layout below stays where it was. */
    private static final int MAX_ROWS = 8;
    private static final int DONE_KEPT = 12; // memory cap only; the visible window is MAX_ROWS

    private final Path busFile;
    private long offset;
    private StringBuilder carry = new StringBuilder();

    private final Deque<String> pendingLog = new ArrayDeque<>();
    private boolean sawSync;
    private boolean syncActive;
    private String repo = "";
    private String from = "";
    private String to = "";
    private String note = "";
    private int manifestEntries;
    private String currentFile = "";
    private final List<String> doneFiles = new ArrayList<>();
    private boolean applying;
    private boolean ended;
    private String syncedFrom;
    private String syncedTo;
    private boolean pendingSwaps;

    public UpdaterFeed(String gameDir) {
        busFile = Paths.get(gameDir, ".bamfos-updater", "bus.log");
        try {
            if (Files.isRegularFile(busFile)) {
                // Clear last boot's bus.log on startup.
                Files.writeString(busFile, "");
            }
        } catch (IOException ignored) {
        }
        if (!Files.isRegularFile(Paths.get(gameDir, "mods", "bamfos-updater-boot.jar"))) {
            pendingLog.add("updater: not present, sync skipped"); // earlymenu owns this line
        }
    }

    /** Reads whatever the engine appended since last frame. Render thread only. */
    public void poll() {
        try {
            if (!Files.isRegularFile(busFile)) {
                return;
            }
            long size = Files.size(busFile);
            if (size <= offset) {
                return;
            }
            try (SeekableByteChannel ch = Files.newByteChannel(busFile)) {
                ch.position(offset);
                ByteBuffer buf = ByteBuffer.allocate((int) Math.min(64 * 1024, size - offset));
                int n = ch.read(buf);
                if (n <= 0) {
                    return;
                }
                offset += n;
                carry.append(new String(buf.array(), 0, n, StandardCharsets.UTF_8));
            }
            int nl;
            while ((nl = carry.indexOf("\n")) >= 0) {
                event(carry.substring(0, nl));
                carry.delete(0, nl + 1);
            }
        } catch (IOException | RuntimeException ignored) {
            // Ignore transient read errors and retry on the next poll.
        }
    }

    private void event(String line) {
        String[] f = line.split("\t", -1);
        switch (f[0]) {
            case "LOG" -> pendingLog.add(f.length > 1 ? f[1] : "");
            case "SYNC" -> {
                sawSync = true;
                syncActive = true;
                repo = f.length > 1 ? f[1] : "";
                from = f.length > 2 ? f[2] : "";
                to = f.length > 3 ? f[3] : "";
            }
            case "NOTE" -> note = f.length > 1 ? f[1] : "";
            case "MANIFEST" -> manifestEntries = parseInt(f, 1);
            case "FILE" -> currentFile = f.length > 1 ? f[1] : "";
            case "FDONE" -> {
                currentFile = "";
                if (f.length > 1) {
                    doneFiles.add(f[1]);
                    while (doneFiles.size() > DONE_KEPT) {
                        doneFiles.remove(0);
                    }
                }
            }
            case "PHASE" -> applying = true;
            case "SYNCED" -> {
                syncedFrom = f.length > 1 ? f[1] : "";
                syncedTo = f.length > 2 ? f[2] : "";
            }
            case "PENDING" -> pendingSwaps = true;
            case "END" -> {
                syncActive = false;
                ended = true;
                if (sawSync && f.length > 1 && !"ok".equals(f[1])) {
                    pendingLog.add("updater: sync did not finish, boot continues"); // the block freezing needs a why
                }
            }
            default -> {
                // Ignore unknown events from newer engines and keep rendering what we recognize.
            }
        }
    }

    /** Drains one queued dmesg line for the scrolling log, null when none. */
    public String nextLogLine() {
        return pendingLog.pollFirst();
    }

    /** The DEL gate + footer swap hang off this. */
    public boolean syncActive() {
        return syncActive;
    }

    /** The release-notes headline for the status line, "" until the engine sends one. */
    public String note() {
        return note;
    }

    /** The Award-style updater POST block, rebuilt per frame like every other terminal string. */
    public List<Line> postBlock(int frame) {
        if (!sawSync) {
            return List.of();
        }
        List<Line> out = new ArrayList<>();
        out.add(new Line("Updating BAMFOS", false));
        out.add(new Line("Update Channel   : " + repo, false));
        out.add(new Line("Installed        : " + from, false));
        out.add(new Line("Target           : " + to, false));
        if (manifestEntries > 0) {
            out.add(new Line("Manifest         : " + manifestEntries + " entries  OK", false));
        }
        if (!currentFile.isEmpty()) {
            char spin = SPINNER[(frame / 3) % SPINNER.length];
            out.add(new Line("Fetching         : " + currentFile + "  " + spin, false));
        }
        for (String f : doneFiles) {
            out.add(new Line("  " + dotted(f), false));
        }
        if (applying) {
            out.add(new Line("Verifying ............ OK", false));
            out.add(new Line("Applying ............. " + (ended ? "OK" : ""), false));
        }
        if (syncedFrom != null) {
            out.add(new Line("BAMFOS UPDATED FROM " + syncedFrom + " TO " + syncedTo + ".", true));
        }
        if (pendingSwaps) {
            out.add(new Line("BIOS UPDATE COMPLETE. REBOOT TO APPLY.", true));
        }
        // Keep the newest rows visible by trimming from the top.
        return out.size() <= MAX_ROWS ? out : out.subList(out.size() - MAX_ROWS, out.size());
    }

    private static String dotted(String name) {
        int pad = Math.max(3, 42 - name.length());
        return name + " " + ".".repeat(pad) + " ok";
    }

    private static int parseInt(String[] f, int i) {
        try {
            return f.length > i ? Integer.parseInt(f[i]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
