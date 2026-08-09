package com.retro.bamfos.updater.engine.report;

import com.retro.bamfos.updater.engine.door.SyncCore;
import com.retro.bamfos.updater.engine.guard.HeldStrikes;
import com.retro.bamfos.updater.engine.util.HashUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

// Evaluates on-disk updater state and returns at most one prioritized trigger with a dedup fingerprint.
final class Triggers {

    /** Consecutive failed boots before firing FAILED_STREAK. Keep this in sync with the message
     *  in Trigger.FAILED_STREAK. */
    private static final int STREAK_TO_REPORT = 3;

    private static final String FLOOR = "core-0000.jar";

    /** Our own bookkeeping files are a handful of rows. The cap is here for the hand-swollen one, because this
     *  runs on the boot path. */
    private static final int ROW_CAP = 400;

    /** Fixed set of report row labels (kept as an enum like Shape so arbitrary disk data can never appear
     *  as a label). */
    enum Label {
        REFUSED("refused"),
        STILL_STANDING("still standing"),
        GIVEN_UP_ON("files given up on"),
        FAILED_OUTRIGHT("failed outright");

        private final String text;

        Label(String text) {
            this.text = text;
        }

        String text() {
            return text;
        }
    }

    /** One labelled evidence row. The PADDING lives in ReportBody, because a second file deciding column widths
     *  is a second file that can disagree with the first about them. */
    record Line(Label label, Shape value) {
    }

    /** Validated fields parsed from a core failure note: timestamp, Java version, generation name,
     *  and two exception class names (never raw exception messages). */
    record Note(long at, int java, Shape core, Shape cause, Shape under) {
    }

    /** Fault details written to the report (no paths or free-form text). `note` is only set for NO_ENGINE. */
    record Fault(Trigger trigger, Shape fingerprint, List<Line> evidence, Note note) {
    }

    /** Pairs a Fault with the core note path to delete after writing (only set for NO_ENGINE). */
    record Fired(Fault fault, Path notePath) {
    }

    /** Picks the first matching trigger in priority order so we emit at most one report per boot. */
    static Optional<Fired> pick(Path dir, long now) {
        Optional<Fired> note = noEngine(dir);
        if (note.isPresent()) {
            return note;
        }
        Optional<Fired> refused = refusedRows(dir.resolve(".bamfos-updater/core/core-rejected"),
                Trigger.CORE_REFUSED, Shape::gen, ReportState.lastReportAt(dir));
        if (refused.isPresent()) {
            return refused;
        }
        Optional<Fired> engine = refusedRows(dir.resolve(".bamfos-updater/engine/engine-rejected"),
                Trigger.ENGINE_REFUSED, Shape::sha, ReportState.lastReportAt(dir));
        if (engine.isPresent()) {
            return engine;
        }
        Optional<Fired> floor = floorOnly(dir);
        if (floor.isPresent()) {
            return floor;
        }
        Optional<Fired> held = heldSettled(dir, now);
        if (held.isPresent()) {
            return held;
        }
        return failedStreak(dir);
    }

    /** T1: turns a core startup failure note into a full report and deletes the note once written. */
    private static Optional<Fired> noEngine(Path dir) {
        Optional<Path> note = ReportSlot.pendingCoreNote(dir);
        if (note.isEmpty()) {
            return Optional.empty();
        }
        List<String> fields = noteFields(note.get());
        if (fields.isEmpty()) {
            return Optional.empty(); // Skip unreadable notes rather than guessing their contents.
        }
        Note read = new Note(number(field(fields, "at")), (int) number(field(fields, "java")),
                gen(field(fields, "core")), type(field(fields, "cause")), type(field(fields, "cause-2")));
        // Fingerprint on the validated causes so corrupted notes dedupe together instead of writing
        // a new report every time.
        String naming = read.cause() + "/" + read.under();
        return Optional.of(new Fired(new Fault(Trigger.NO_ENGINE,
                fingerprint(Trigger.NO_ENGINE, naming), List.of(), read), note.get()));
    }

    /**
     * T2/T3: core (by generation name) and engine (by sha) refusals. Both guards require two strikes
     * before recording a refusal so a single power cut or alt-F4 won't trip them.
     */
    private static Optional<Fired> refusedRows(Path file, Trigger trigger, Function<String, Shape> gate,
                                               long since) {
        List<String> fresh = new ArrayList<>();
        for (String[] parts : rows(file)) {
            if (parts.length < 2 || parts[0].trim().isEmpty()) {
                continue;
            }
            if (stamp(parts[1]) > since) {
                fresh.add(parts[0].trim());
            }
        }
        if (fresh.isEmpty()) {
            return Optional.empty();
        }
        Collections.sort(fresh);
        List<Line> evidence = new ArrayList<>();
        for (String name : fresh) {
            evidence.add(new Line(Label.REFUSED, gate.apply(name)));
        }
        return Optional.of(new Fired(new Fault(trigger, fingerprint(trigger, String.join(",", fresh)),
                evidence, null), null));
    }

    /**
     * T4: all downloaded core generations were refused and we're on the embedded stub floor (a state,
     * so fingerprint dedupe matters). Newer generations still install; repair clears previously refused ones.
     */
    private static Optional<Fired> floorOnly(Path dir) {
        List<String> refused = firstFields(rows(dir.resolve(".bamfos-updater/core/core-rejected")));
        if (refused.isEmpty()) {
            return Optional.empty();
        }
        if (!coreJars(dir).equals(List.of(FLOOR))) {
            return Optional.empty();
        }
        Collections.sort(refused);
        List<Line> evidence = new ArrayList<>();
        for (String name : refused) {
            evidence.add(new Line(Label.REFUSED, gen(name)));
        }
        evidence.add(new Line(Label.STILL_STANDING, gen(FLOOR)));
        return Optional.of(new Fired(new Fault(Trigger.FLOOR_ONLY,
                fingerprint(Trigger.FLOOR_ONLY, String.join(",", refused)), evidence, null), null));
    }

    /**
     * T5: fires when held-back files exist and sync didn't converge (matching ServerDoor.selfHeal).
     * Logs only the count, not local paths, while feeding the paths into the dedupe fingerprint.
     */
    private static Optional<Fired> heldSettled(Path dir, long now) {
        if (!HeldStrikes.allExhausted(dir, now) || "ok".equals(SyncCore.lastSyncResult(dir))) {
            return Optional.empty();
        }
        List<String> paths = new ArrayList<>(HeldStrikes.exhaustedNow(dir, now));
        if (paths.isEmpty()) {
            return Optional.empty();
        }
        Collections.sort(paths);
        List<Line> evidence = List.of(new Line(Label.GIVEN_UP_ON, Shape.number(paths.size())));
        return Optional.of(new Fired(new Fault(Trigger.HELD_SETTLED,
                fingerprint(Trigger.HELD_SETTLED, String.join(",", paths)), evidence, null), null));
    }

    /**
     * T6: repeated outright failures (see ReportState.noteSyncResult). Uses an empty fingerprint so one
     * streak stays in one report file regardless of intervening offline/limited boots, with a fresh count.
     */
    private static Optional<Fired> failedStreak(Path dir) {
        int streak = ReportState.failedStreak(dir);
        if (streak < STREAK_TO_REPORT) {
            return Optional.empty();
        }
        List<Line> evidence = List.of(new Line(Label.FAILED_OUTRIGHT, Shape.number(streak)));
        return Optional.of(new Fired(new Fault(Trigger.FAILED_STREAK,
                fingerprint(Trigger.FAILED_STREAK, ""), evidence, null), null));
    }

    /** Parses the core note fields from note-format through cause-2. */
    private static List<String> noteFields(Path note) {
        List<String> out = new ArrayList<>();
        boolean in = false;
        for (String line : lines(note)) {
            if (line.startsWith("note-format")) {
                in = true;
            }
            if (in && line.isEmpty()) {
                break;
            }
            if (in) {
                out.add(line);
            }
        }
        return out;
    }

    private static String field(List<String> fields, String key) {
        for (String line : fields) {
            if (line.startsWith(key + " ")) {
                return line.substring(key.length() + 1).trim();
            }
        }
        return "";
    }

    /** Accepts the literal "none" (used when there's no core or second exception) as a valid value
     *  instead of treating it as invalid. */
    private static Shape gen(String raw) {
        return "none".equals(raw) ? Shape.NONE : Shape.gen(raw);
    }

    private static Shape type(String raw) {
        return "none".equals(raw) ? Shape.NONE : Shape.type(raw);
    }

    private static List<String> coreJars(Path dir) {
        List<String> out = new ArrayList<>();
        try (Stream<Path> listing = Files.list(dir.resolve(".bamfos-updater/core"))) {
            for (Path p : listing.filter(Files::isRegularFile).sorted().toList()) {
                String name = p.getFileName().toString();
                if (name.startsWith("core-") && name.endsWith(".jar")) {
                    out.add(name);
                }
            }
        } catch (IOException | RuntimeException none) {
            return out;
        }
        return out;
    }

    /** Streams bookkeeping rows with a cap instead of readAllLines so an overgrown file can't stall boot. */
    private static List<String[]> rows(Path file) {
        List<String[]> out = new ArrayList<>();
        for (String line : lines(file)) {
            out.add(line.split("\t"));
        }
        return out;
    }

    private static List<String> lines(Path file) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        try (Stream<String> stream = Files.lines(file, StandardCharsets.UTF_8)) {
            stream.limit(ROW_CAP).forEach(out::add);
        } catch (IOException | RuntimeException unreadable) {
            // Treat unreadable files as empty so report generation doesn't abort halfway through.
            out.clear();
        }
        return out;
    }

    private static List<String> firstFields(List<String[]> rows) {
        List<String> out = new ArrayList<>();
        for (String[] parts : rows) {
            String first = parts[0].trim();
            if (!first.isEmpty()) {
                out.add(first);
            }
        }
        return out;
    }

    /** Treats unparseable timestamps as recent so we check dedupe state instead of dropping the fault. */
    private static long stamp(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException handEdited) {
            return Long.MAX_VALUE;
        }
    }

    /** Parses a long or returns 0 if invalid, so one bad field doesn't break the whole report. */
    private static long number(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException handEdited) {
            return 0L;
        }
    }

    private static Shape fingerprint(Trigger trigger, String naming) {
        return Shape.sha(HashUtils.sha256((trigger.word() + "|" + naming).getBytes(StandardCharsets.UTF_8)));
    }

    private Triggers() {
    }
}
