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

// Tails .bamfos-updater/bus.log and turns updater events into the POST screen status lines (file-based so a missing or broken updater never breaks earlywindow).
public final class UpdaterFeed {

    /** One rendered row of the updater POST block; bright marks the pack's CAPS finale lines. */
    public record Line(String text, boolean bright) {
    }

    private static final char[] SPINNER = {'|', '/', '-', '\\'};
    /** The hardware block's own word for a device that has not answered yet, reused so the updater rows sit
     *  in the same register as the Display Adapter row above them. */
    private static final String DETECTING = "Detecting ...";
    /* Keep the status block at a fixed height and scroll it instead of growing downward, it
       keeps a fixed footprint and SCROLLS ITSELF, each line past the max shifting everything up
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
    private String closingRow; // Final status line when END isn't ok (null while still running).
    private final boolean shimPresent;
    private boolean identified; // the door answered IDENT, so the hardware rows carry real values
    private String identChannel = "";
    private String identEngine = "";
    private String identLock = "";

    public UpdaterFeed(String gameDir) {
        busFile = Paths.get(gameDir, ".bamfos-updater", "bus.log");
        try {
            if (Files.isRegularFile(busFile)) {
                // Clear last boot's bus.log on startup.
                Files.writeString(busFile, "");
            }
        } catch (IOException ignored) {
        }
        shimPresent = Files.isRegularFile(Paths.get(gameDir, "mods", "bamfos-updater-boot.jar"));
        if (!shimPresent) {
            // Use the same kmod prefix as the updater when the boot shim isn't installed.
            pendingLog.add("com.bamf.updater: kmod not found, package manager offline");
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
            case "IDENT" -> {
                identified = true;
                identChannel = channelName(f.length > 1 ? f[1] : "");
                identEngine = f.length > 2 ? f[2] : "";
                identLock = f.length > 3 ? f[3] : "";
            }
            case "SYNC" -> {
                sawSync = true;
                syncActive = true;
                repo = channelName(f.length > 1 ? f[1] : "");
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
                // the block has to TERMINATE, not just stop being fed. postBlock() rebuilds from sawSync
                // every frame for the rest of the boot, so a spinner row left standing spins forever and a
                // screen of "file ... ok" rows reads as a finished update, either one contradicting the
                // honest skip line the engine printed underneath (BootBus.end: ok | skip | limited | fail)
                String state = f.length > 1 ? f[1] : "";
                currentFile = "";
                if (sawSync && !"ok".equals(state)) {
                    closingRow = "limited".equals(state)
                            ? "Update paused ........ RATE LIMITED"
                            : "Update stopped ....... FAILED";
                }
                // only a real "fail" earns the why-line. A rate-limited END already printed its own honest
                // line, and stacking "sync aborted" under it renders two rows that contradict each other
                if (sawSync && "fail".equals(state)) {
                    // Tell the user boot continues: updater failures never block game launch.
                    pendingLog.add("updater: sync aborted, boot continues");
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

    /**
     * The updater's three rows for the detected-hardware block:
     * the channel a sync would pull from, the engine build that would run it, and the release lock. All three
     * report state the engine actually read, never a default this side guessed at, so each one reads
     * "Detecting ..." until the door sends IDENT during the locator phase, exactly how the Display Adapter
     * row above them behaves. Empty when the shim jar is not in mods/: there is no channel, no build and no
     * lock to report, and the log already said the kmod is missing, so three NOT INSTALLED rows would only
     * say it three more times.
     */
    public List<String> hardwareRows() {
        if (!shimPresent) {
            return List.of();
        }
        String lock = identLock.isEmpty() ? "none" : identLock + "  PINNED";
        return List.of(
                "Update Channel   : " + answered(identChannel, "  OK"),
                "Updater Module   : " + answered(identEngine, "  OK"),
                // an empty pin is the answer "not pinned", so this row has no blank case to guard
                "Release Lock     : " + (identified ? lock : DETECTING));
    }

    /** A row's value once the door has answered, else the word for a device that has not. A BLANK field
     *  counts as no answer, because `Update Channel   :   OK` reports a device with no name, which is the one
     *  thing these rows exist not to do (a hand-edited `repo=` with nothing after it lands here). */
    private String answered(String field, String trailer) {
        return identified && !field.isEmpty() ? field + trailer : DETECTING;
    }

    /** Returns just the repo name without `owner/` so screen recordings don't expose a GitHub username. */
    private static String channelName(String repo) {
        int slash = repo.lastIndexOf('/');
        return slash >= 0 && slash + 1 < repo.length() ? repo.substring(slash + 1) : repo;
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
        if (closingRow != null) {
            out.add(new Line(closingRow, true)); // Highlight in bright text to match the final status lines.
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
