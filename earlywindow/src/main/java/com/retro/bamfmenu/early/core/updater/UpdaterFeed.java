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
     * One rendered row of the updater POST block (`bright` = CAPS finale, `fill` = green bar prefix length).
     * `alert` outranks `bright` to draw in error red (used for the reboot-pending line).
     */
    public record Line(String text, boolean bright, int fill, boolean alert) {
        /** Non-bar row (`fill = -1`, since `0` is a valid 0% bar that still draws a dim track). */
        public Line(String text, boolean bright) {
            this(text, bright, -1, false);
        }

        /** Standard bar row (non-alert). */
        public Line(String text, boolean bright, int fill) {
            this(text, bright, fill, false);
        }

        /** Factory for red alert rows so callers don't pass a 4th boolean. */
        public static Line alert(String text) {
            return new Line(text, true, -1, true);
        }
    }

    private static final char[] SPINNER = {'|', '/', '-', '\\'};
    /** Aligns the progress bar under the value column of "Fetching         : " (19 chars). */
    private static final int BAR_INDENT = 19;
    /** Aligns the apply progress bar 4 chars further right to match its dotted label rows. */
    private static final int APPLY_BAR_INDENT = 23;
    private static final int BAR_WIDTH = 38;
    /** Reused from the hardware block so the updater row matches Display Adapter while probing. */
    private static final String DETECTING = "Detecting ...";
    // Keep the status block at a fixed height and scroll new lines instead of pushing the layout down.
    private static final int MAX_ROWS = 8;
    private static final int DONE_KEPT = 12; // memory cap only; the visible window is MAX_ROWS

    private final Path busFile;
    private long offset;
    /**
     * Raw byte buffer decoded to UTF-8 only at `\n` boundaries.
     * Chunk reads can split multi-byte UTF-8 filenames (producing U+FFFD), while `\n` is always a clean boundary.
     */
    private java.io.ByteArrayOutputStream carry = new java.io.ByteArrayOutputStream();

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
    /** Actual file count recorded by the engine (-1 when not reported, such as older engines or staged runs). */
    private int landed = -1;
    private boolean pendingSwaps;
    private String closingRow; // Final status line when END isn't ok (null while still running).
    private final boolean shimPresent;
    private boolean identified; // True once we get IDENT with the channel name.
    private String identChannel = "";
    private String channelState = ""; // Channel status from the updater (empty until reported).

    /* Step 0 apply state (BootBus.applyBegin / applyStep / applyEnd). Kept separate from the sync
       fields above since both can run in one boot and an apply shouldn't look like a download. */
    private boolean sawApply;
    private String applyVersion = "";
    private String applyPhase = ""; // remove | install | verify, "" once it is over
    private String applyPath = "";
    private int removeDone;
    private int removeTotal;
    private int placeDone;
    private int placeTotal;
    private int verifyDone;
    private int verifyTotal;
    private String applyState = ""; // ok | partial, "" while it is still running

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
                carry.write(buf.array(), 0, n);
            }
            byte[] pending = carry.toByteArray();
            int lastNl = -1;
            for (int i = pending.length - 1; i >= 0; i--) {
                if (pending[i] == '\n') {
                    lastNl = i;
                    break;
                }
            }
            if (lastNl < 0) {
                return; // Incomplete line; keep the trailing bytes until the rest arrives.
            }
            String text = new String(pending, 0, lastNl + 1, StandardCharsets.UTF_8);
            carry.reset();
            carry.write(pending, lastNl + 1, pending.length - lastNl - 1);
            int from = 0;
            int nl;
            while ((nl = text.indexOf('\n', from)) >= 0) {
                event(text.substring(from, nl));
                from = nl + 1;
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
            case "APPLY" -> {
                sawApply = true;
                // Block opening the BIOS with DEL while files are being moved, same as during sync.
                syncActive = true;
                applyVersion = f.length > 1 ? f[1] : "";
                removeTotal = parseInt(f, 2);
                placeTotal = parseInt(f, 3);
            }
            case "ASTEP" -> {
                applyPhase = f.length > 1 ? f[1] : "";
                applyPath = f.length > 2 ? f[2] : "";
                int done = parseInt(f, 3);
                int total = parseInt(f, 4);
                switch (applyPhase) {
                    case "remove" -> {
                        removeDone = done;
                        removeTotal = total;
                    }
                    case "install" -> {
                        placeDone = done;
                        placeTotal = total;
                    }
                    case "verify" -> {
                        verifyDone = done;
                        verifyTotal = total;
                    }
                    default -> {
                        // Ignore unknown phases from newer engines instead of guessing a row name.
                    }
                }
            }
            case "ADONE" -> {
                applyState = f.length > 1 ? f[1] : "";
                applyPhase = ""; // Clear the bar and spinner to mark the block finished.
                applyPath = "";
                syncActive = false;
            }
            case "PHASE" -> applying = true;
            case "SYNCED" -> {
                syncedFrom = f.length > 1 ? f[1] : "";
                syncedTo = f.length > 2 ? f[2] : "";
                // Older engines only send three fields; use -1 so we fall back to the planned count instead of 0.
                landed = f.length > 3 ? parseInt(f, 3) : -1;
            }
            case "PENDING" -> pendingSwaps = true;
            case "END" -> {
                syncActive = false;
                ended = true;
                // Terminate the sync block explicitly so postBlock() doesn't leave a spinner stuck
                // or partial "ok" rows above the final status (BootBus.end: ok|skip|limited|offline|no-release|fail).
                String state = f.length > 1 ? f[1] : "";
                currentFile = "";
                // Terminate the apply block too: if step 0 fails without ADONE (e.g. disk full),
                // mark it "partial" so the apply spinner doesn't stay frozen on screen.
                if (sawApply && applyState.isEmpty()) {
                    applyState = "partial";
                    applyPhase = "";
                    applyPath = "";
                }
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
                    pendingLog.add("updater: sync aborted, continuing to boot");
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

    /** True if this boot staged updates for next boot; scoped to this boot because the ctor truncates prior feeds. */
    public boolean pendingSwaps() {
        return pendingSwaps;
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
        if (!sawSync && !sawApply) {
            return List.of();
        }
        List<Line> out = new ArrayList<>();
        if (sawApply) {
            applyRows(out, frame);
        }
        if (!sawSync) {
            return out.size() <= MAX_ROWS ? out : out.subList(out.size() - MAX_ROWS, out.size());
        }
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
            // Also show below a version change so partial failures aren't hidden by "UPDATED FROM X TO Y".
            int held = landed >= 0 ? planned - landed : 0;
            if (held > 0) {
                out.add(new Line(held + (held == 1 ? " FILE HELD BACK." : " FILES HELD BACK."), true));
            }
        }
        if (pendingSwaps) {
            out.add(Line.alert("BIOS UPDATE COMPLETE. REBOOT TO APPLY.")); // alert red for reboot-pending notice
        }
        if (closingRow != null) {
            out.add(new Line(closingRow, true)); // Highlight in bright text to match the final status lines.
        }
        // Keep the newest rows visible by trimming from the top.
        return out.size() <= MAX_ROWS ? out : out.subList(out.size() - MAX_ROWS, out.size());
    }

    /**
     * Renders step 0 journal apply progress (e.g. factory reset).
     * Keeps one row per phase instead of overwriting so removals stay visible once installs begin.
     */
    private void applyRows(List<Line> out, int frame) {
        out.add(new Line("Applying BAMFOS", false));
        if (!applyVersion.isEmpty()) {
            out.add(new Line("Target           : " + applyVersion, false));
        }
        if (removeTotal > 0) {
            out.add(new Line(phaseRow("Removing", removeDone, removeTotal), false));
        }
        if (placeTotal > 0) {
            out.add(new Line(phaseRow("Installing", placeDone, placeTotal), false));
        }
        if (verifyTotal > 0) {
            out.add(new Line(phaseRow("Verifying", verifyDone, verifyTotal), false));
        }
        if (!applyPhase.isEmpty()) {
            // Track only the active phase in the progress bar; combining all three phases makes the bar
            // stall and jump because their sizes vary wildly.
            ProgressBar.Row bar = ProgressBar.count(APPLY_BAR_INDENT, BAR_WIDTH, phaseDone(), phaseTotal());
            if (bar != null) {
                out.add(new Line(bar.text(), false, bar.fill()));
            }
            out.add(new Line("  " + applyPath + "  " + SPINNER[(frame / 3) % SPINNER.length], false));
        }
        if ("partial".equals(applyState)) {
            // Highlight non-ok end states like the sync summary row (skip `ok` since each phase already shows OK).
            out.add(new Line("Apply stopped ........ INCOMPLETE", true));
        }
    }

    private int phaseDone() {
        return switch (applyPhase) {
            case "remove" -> removeDone;
            case "install" -> placeDone;
            default -> verifyDone;
        };
    }

    private int phaseTotal() {
        return switch (applyPhase) {
            case "remove" -> removeTotal;
            case "install" -> placeTotal;
            default -> verifyTotal;
        };
    }

    /**
     * Formats a phase row with dot padding aligned to the sync Verifying/Applying rows.
     * Appends OK only when all items in the phase are done.
     */
    private static String phaseRow(String label, int done, int total) {
        String value = done >= total
                ? total + (total == 1 ? " file  OK" : " files  OK")
                : done + " of " + total;
        return label + " " + ".".repeat(Math.max(3, 21 - label.length())) + " " + value;
    }

    /**
     * Builds the uppercase final status line from whether the version changed and how many files landed.
     * Avoids printing "UPDATED FROM X TO X" on same-version repairs, and falls back to planned counts when `landed == -1`.
     */
    private String finale() {
        if (landed == 0 && planned > 0) {
            // Planned downloads > 0 with zero landed: close on INCOMPLETE.
            // `planned > 0` is required because delete-only rollbacks converge cleanly with zero downloads.
            return "Update stopped ....... INCOMPLETE";
        }
        if (!syncedFrom.equals(syncedTo)) {
            // Check tag direction so pulling a bad release prints ROLLED BACK to match latest.log.
            return (rolledBack(syncedFrom, syncedTo) ? "BAMFOS ROLLED BACK FROM " : "BAMFOS UPDATED FROM ")
                    + syncedFrom + " TO " + syncedTo + ".";
        }
        if (landed > 0 && landed < planned) {
            return "BAMFOS REPAIRED " + landed + " OF " + planned + " FILES ON " + syncedTo + ".";
        }
        // The engine doesn't send SYNCED when only updater jars changed, so planned is always > 0 here.
        int count = landed < 0 ? planned : landed;
        return "BAMFOS REPAIRED " + count + (count == 1 ? " FILE ON " : " FILES ON ") + syncedTo + ".";
    }

    /**
     * Local duplicate of the backward-move check in engine `TagOrder` (duplicated so earlywindow stays decoupled).
     * Returns true only when provably backward (unreadable or ambiguous tags return false); keep in sync with `TagOrder`.
     */
    static boolean rolledBack(String from, String to) {
        int[] left = tagParts(tagCore(from));
        int[] right = tagParts(tagCore(to));
        if (left == null || right == null) {
            return false;
        }
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int l = i < left.length ? left[i] : 0; // 0-fill so v0.2 and v0.2.0 stay the same version
            int r = i < right.length ? right[i] : 0;
            if (l != r) {
                return r < l;
            }
        }
        // Equal numbers: a bare release outranks a prerelease suffix (semver), so moving to a suffixed tag is a downgrade.
        return tagSuffix(from).isEmpty() && !tagSuffix(to).isEmpty();
    }

    /** Drops a leading v and everything from the first suffix marker, leaving the dotted numbers. */
    private static String tagCore(String tag) {
        String s = tag.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.startsWith("v")) {
            s = s.substring(1);
        }
        int cut = s.indexOf('-');
        int plus = s.indexOf('+');
        if (plus >= 0 && (cut < 0 || plus < cut)) {
            cut = plus;
        }
        return cut < 0 ? s : s.substring(0, cut);
    }

    /** Returns the suffix starting at its delimiter from the raw tag, or "" if none. */
    private static String tagSuffix(String tag) {
        String s = tag.trim();
        int cut = s.indexOf('-');
        int plus = s.indexOf('+');
        if (plus >= 0 && (cut < 0 || plus < cut)) {
            cut = plus;
        }
        return cut < 0 ? "" : s.substring(cut);
    }

    /** null for anything that is not dot-separated digits, because a half-read tag must not become a direction. */
    private static int[] tagParts(String core) {
        if (core.isEmpty()) {
            return null;
        }
        String[] cell = core.split("\\.", -1);
        int[] out = new int[cell.length];
        for (int i = 0; i < cell.length; i++) {
            if (cell[i].isEmpty() || cell[i].length() > 9) {
                return null; // a 20-digit "version" is a date or a hash, not a version
            }
            for (int j = 0; j < cell[i].length(); j++) {
                if (!Character.isDigit(cell[i].charAt(j))) {
                    return null;
                }
            }
            out[i] = Integer.parseInt(cell[i]);
        }
        return out;
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
