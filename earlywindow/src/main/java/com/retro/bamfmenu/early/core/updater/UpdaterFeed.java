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
import java.util.stream.Stream;

// Tails .bamfos-updater/bus.log and turns updater events into the POST screen status lines (file-based so a missing or broken updater never breaks earlywindow).
public final class UpdaterFeed {

    /**
     * One rendered row of the updater POST block; bright marks the pack's CAPS finale lines. `fill` is the
     * download bar's green prefix length, 0 on every other row, and the renderer paints those chars a second
     * time in green over the dim row.
     */
    public record Line(String text, boolean bright, int fill) {
        /** Non-bar row (`fill = -1`, since `0` is a valid 0% bar that still draws a dim track). */
        public Line(String text, boolean bright) {
            this(text, bright, -1);
        }
    }

    private static final char[] SPINNER = {'|', '/', '-', '\\'};
    /** Aligns the progress bar under the value column of "Fetching         : " (19 chars). */
    private static final int BAR_INDENT = 19;
    private static final int BAR_WIDTH = 38;
    /** Reused from the hardware block so the updater row matches Display Adapter while probing. */
    private static final String DETECTING = "Detecting ...";
    // Keep the status block at a fixed height and scroll new lines instead of pushing the layout down.
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
    private int planned; // File count from PLAN, used to format the final repair summary.
    private String currentFile = "";
    private String currentReason = ""; // Short reason from the engine for why this file changed (empty if none).
    private long progDone;   // Bytes transferred for the current file (0 when no bar should be drawn).
    private long progTotal;  // expected byte length; 0 when unknown, which hides the progress bar
    private final List<String> doneFiles = new ArrayList<>();
    private boolean applying;
    private boolean ended;
    private String syncedFrom;
    private String syncedTo;
    private boolean pendingSwaps;
    private String closingRow; // Final status line when END isn't ok (null while still running).
    private final boolean shimPresent;
    private boolean identified; // True once we get IDENT with the channel name.
    private String identChannel = "";
    private String channelState = ""; // Channel status from the updater (empty until reported).

    public UpdaterFeed(String gameDir) {
        busFile = Paths.get(gameDir, ".bamfos-updater", "bus.log");
        try {
            if (Files.isRegularFile(busFile)) {
                // Clear last boot's bus.log on startup.
                Files.writeString(busFile, "");
            }
        } catch (IOException ignored) {
        }
        shimPresent = shimIn(Paths.get(gameDir, "mods"));
        if (!shimPresent) {
            // Use the same kmod prefix as the updater when the boot shim isn't installed.
            pendingLog.add("com.bamf.updater: kmod not found, package manager offline");
        }
    }

    /**
     * Checks `mods/` for the managed shim or `bamfos-updater-boot*.jar` (manual installs keep version suffixes).
     * Duplicated from `EmbeddedToken.findShim` so earlywindow stays decoupled from updater classes.
     */
    private static boolean shimIn(Path mods) {
        if (Files.isRegularFile(mods.resolve("bamfos-updater-boot.jar"))) {
            return true;
        }
        try (Stream<Path> listing = Files.list(mods)) {
            return listing.anyMatch(UpdaterFeed::looksLikeTheShim);
        } catch (IOException | RuntimeException noModsDir) {
            return false;
        }
    }

    private static boolean looksLikeTheShim(Path p) {
        String n = p.getFileName().toString();
        // Require .jar at the end so disabled jars like bamfos-updater-boot.jar.disabled are ignored.
        return n.startsWith("bamfos-updater-boot") && n.endsWith(".jar") && Files.isRegularFile(p);
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
            }
            case "CHANNEL" -> channelState = f.length > 1 ? f[1] : "";
            case "SYNC" -> {
                sawSync = true;
                syncActive = true;
                repo = channelName(f.length > 1 ? f[1] : "");
                from = f.length > 2 ? f[2] : "";
                to = f.length > 3 ? f[3] : "";
            }
            case "NOTE" -> note = f.length > 1 ? f[1] : "";
            case "MANIFEST" -> manifestEntries = parseInt(f, 1);
            case "PLAN" -> planned = parseInt(f, 1);
            case "FILE" -> {
                currentFile = f.length > 1 ? f[1] : "";
                // Older engines only send two fields without a reason, which is fine.
                currentReason = f.length > 2 ? f[2] : "";
                progDone = 0;
                progTotal = 0; // Reset progress until the first FPROG arrives for the new file.
            }
            case "FPROG" -> {
                progDone = parseLong(f, 2);
                progTotal = parseLong(f, 3);
            }
            case "FDONE" -> {
                currentFile = "";
                currentReason = "";
                progDone = 0;
                progTotal = 0;
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
                // honest skip line the engine printed underneath
                // (BootBus.end: ok | skip | limited | no-release | fail)
                String state = f.length > 1 ? f[1] : "";
                currentFile = "";
                if (sawSync && !"ok".equals(state)) {
                    // Match the NO CARRIER label on the hardware row when offline instead of showing FAILED next to it.
                    closingRow = switch (state) {
                        case "limited" -> "Update paused ........ RATE LIMITED";
                        case "offline" -> "Update stopped ....... NO CARRIER";
                        default -> "Update stopped ....... FAILED";
                    };
                }
                // Match "fail" literally: `limited` and `no-release` print their own lines,
                // and unknown future states should stay on the generic FAILED row without contradicting it.
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

    /**
     * Release-notes headline from the engine ("" until sent).
     * Unused today (kept for a future what's-new display); remove along with the NOTE arm if dropped.
     */
    public String note() {
        return note;
    }

    /**
     * Hardware-block row showing the update channel and its status for this boot.
     * Filled in two steps: IDENT sets the channel name, then the status appends when the updater finishes.
     * Empty when no shim jar is in mods/, though a live IDENT takes precedence over filename checks.
     */
    public List<String> hardwareRows() {
        if (!shimPresent && !identified) {
            return List.of();
        }
        return List.of("Update Channel   : " + answered(identChannel, indicator()));
    }

    /**
     * Maps the updater channel status (`BootBus.channel`) to the trailing status label.
     * IDLE is normal when already up to date; unknown states leave the label blank.
     */
    private String indicator() {
        return switch (channelState) {
            case "ok" -> "  OK";
            case "fail" -> "  FAILED";
            case "idle" -> "  IDLE";
            case "no-carrier" -> "  NO CARRIER";
            case "pinned" -> "  PINNED";
            default -> "";
        };
    }

    /**
     * Returns the channel name from IDENT, or the fallback label if none arrived.
     * Blank values (like an empty `repo=`) count as missing so we never display `Update Channel   :   OK`.
     */
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
            // Show the progress bar indented under the value column when total size is known;
            // omit when unknown so we don't draw a fake bar.
            ProgressBar.Row bar = ProgressBar.of(BAR_INDENT, BAR_WIDTH, progDone, progTotal);
            if (bar != null) {
                out.add(new Line(bar.text(), false, bar.fill()));
            }
        }
        for (String f : doneFiles) {
            out.add(new Line("  " + dotted(f), false));
        }
        if (applying) {
            out.add(new Line("Verifying ............ OK", false));
            out.add(new Line("Applying ............. " + (ended ? "OK" : ""), false));
        }
        if (syncedFrom != null) {
            out.add(new Line(finale(), true));
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

    /** The pack host's CAPS finale, worded off whether the VERSION actually moved. A converged box runs a whole
     *  sync on the routine repair path (an antivirus ate one managed jar), with the tag set to the version it
     *  is already on, and the block used to close on BAMFOS UPDATED FROM v0.2.6 TO v0.2.6, which is the
     *  brightest row on the screen saying nothing true. The engine sends no SYNCED at all when only its own
     *  jars moved, so a zero here cannot happen. */
    private String finale() {
        if (!syncedFrom.equals(syncedTo)) {
            return "BAMFOS UPDATED FROM " + syncedFrom + " TO " + syncedTo + ".";
        }
        return "BAMFOS REPAIRED " + planned + (planned == 1 ? " FILE ON " : " FILES ON ") + syncedTo + ".";
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

    /**
     * Live status line for the BIOS window while an op runs (current file and reason, or "" when idle).
     * Reads the same `BootBus.append` feed as the boot terminal so both stay in sync.
     */
    public String activity() {
        if (currentFile.isEmpty()) {
            return "";
        }
        return currentReason.isEmpty() ? currentFile : currentFile + "   " + currentReason;
    }

    /** Parse as long because multi-gigabyte releases overflow int and break the progress bar. */
    private static long parseLong(String[] f, int i) {
        try {
            return f.length > i ? Long.parseLong(f[i]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
