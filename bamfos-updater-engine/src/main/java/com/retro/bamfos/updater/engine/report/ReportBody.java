package com.retro.bamfos.updater.engine.report;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

// Renders the trouble report using only literals, primitives, and Shape-validated values, putting the user action at the top.
final class ReportBody {

    /** Column offset where report values start. */
    private static final int LABEL = 17;

    /** Bumped from 1 after the format rewrite so older parsers won't misread new reports. */
    private static final int FORMAT = 2;

    private static final DateTimeFormatter LOCAL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss").withZone(ZoneId.systemDefault());

    static String full(Triggers.Fault fault, BoxFacts.Box box, int boots, long firstAt, long now) {
        List<String> out = new ArrayList<>();
        theAsk(out, fault, boots, firstAt);
        whatHappened(out, fault, box, now);
        thisMachine(out, box);
        notInHere(out);
        out.add("End of report. That is everything the updater can report about this machine; if more detail "
                + "is needed, share logs/latest.log.");
        return String.join("\n", out) + "\n";
    }

    private static void theAsk(List<String> out, Triggers.Fault fault, int boots, long firstAt) {
        out.add("BAMFOS UPDATER PROBLEM REPORT");
        out.add("");
        out.add("Send this whole file to the pack owner so they can see what went wrong on this install.");
        // Describe how the report is built rather than promising scrubbing, since only the build behavior
        // can be guaranteed.
        out.add("Every line here is a fixed message, a number, or a validated name. No log text, file paths, "
                + "or tokens are included.");
        out.add("");
        out.add(fault.trigger().whyLine());
        if (boots > 1) {
            out.add("This has now happened " + boots + " times, first on " + stamp(firstAt) + ".");
        }
        out.add("");
    }

    private static void whatHappened(List<String> out, Triggers.Fault fault, BoxFacts.Box box, long now) {
        out.add("WHAT HAPPENED");
        row(out, "fault", fault.trigger().word() + " (" + fault.fingerprint() + ")");
        // Print both clocks on one row so we can compare local millis against GitHub release times.
        row(out, "written", stamp(now) + " / " + now);
        row(out, "report-format", String.valueOf(FORMAT));
        for (Triggers.Line line : fault.evidence()) {
            row(out, line.label().text(), line.value().toString());
        }
        note(out, fault.note(), box);
        out.add("");
    }

    /** Expands the failure note written by the core when an earlier boot failed before the engine could load. */
    private static void note(List<String> out, Triggers.Note note, BoxFacts.Box box) {
        if (note == null) {
            return;
        }
        row(out, "note written", stamp(note.at()) + " / " + note.at());
        row(out, "note java", String.valueOf(note.java()));
        row(out, "note core", note.core().toString());
        row(out, "note cause", note.cause().toString());
        row(out, "note under", note.under().toString());
        // Include the Java version from the failed boot too, in case the player switched Java
        // versions in their launcher between boots.
        if (note.java() > 0 && note.java() != box.java()) {
            out.add("  The note was written on Java " + note.java() + " and this boot is Java " + box.java()
                    + ". Switching Java versions between boots can stop a jar from loading.");
        }
    }

    private static void thisMachine(List<String> out, BoxFacts.Box box) {
        out.add("THIS MACHINE");
        row(out, "side", box.side().word());
        row(out, "os", box.os() + ", " + box.arch());
        row(out, "java", String.valueOf(box.java()));
        row(out, "engine build", box.engineBuild().toString());
        row(out, "pack version", box.packVersion().toString());
        row(out, "release lock", box.releaseLock().toString());
        // Print only whether it's set, never the raw value, in case a token was pasted into the repo field.
        row(out, "repo", box.repoChanged() ? "not the pack owner's" : "the pack owner's");
        // Only print the count, never the pinned filenames.
        row(out, "pins", String.valueOf(box.pins()));
        row(out, "managed files", String.valueOf(box.managed()));
        row(out, "disabled", String.valueOf(box.disabled()));
        row(out, "quarantined", String.valueOf(box.quarantined()));
        row(out, "last sync", box.lastSync().toString());
        row(out, "files held back", box.filesHeldBack() ? "yes" : "no");
        // Being present means an apply didn't finish.
        row(out, "journal", box.journal() ? "present, an apply did not finish" : "absent");
        slots(out, box);
        out.add("");
    }

    private static void slots(List<String> out, BoxFacts.Box box) {
        List<String> cores = new ArrayList<>();
        for (BoxFacts.CoreJar jar : box.cores()) {
            cores.add(jar.gen() + "  " + size(jar.bytes()) + "  " + stamp(jar.at()));
        }
        rows(out, "core slot", cores);
        BoxFacts.EngineSlot engine = box.engine();
        row(out, "engine jar", engine.jar()
                ? "present  " + engine.sha() + "  " + size(engine.bytes()) + "  " + stamp(engine.at())
                : "absent");
        row(out, "engine rollback", engine.good() ? "present" : "absent");
        // A leftover .new file across boots usually points to a file lock or antivirus.
        row(out, "engine staged", engine.staged() ? "present" : "absent");
    }

    /** Privacy disclaimer printed at the top of each report. Keep it accurate to what the report
     *  actually includes (don't claim properties are excluded if any are printed). */
    private static void notInHere(List<String> out) {
        out.add("WHAT IS NOT IN HERE");
        out.add("  no log lines or raw error messages; every line above is built from fixed strings, numbers, "
                + "and validated names");
        out.add("  no usernames, folder paths, personal files, or anything outside the pack folder");
        out.add("  only two values from .bamfos-updater/bamfos-updater.properties: which release this install is "
                + "locked to and how many pins are set (shown in THIS MACHINE)");
        out.add("  the github token, in any form");
        out.add("  logs/latest.log itself (share that separately if asked)");
        out.add("");
    }

    // Report formatting helpers (all values below are literals or validated fields).

    private static void row(List<String> out, String label, String value) {
        out.add("  " + pad(label) + value);
    }

    /** Prints multi-line values aligned under the value column, with the label only on the first row. */
    private static void rows(List<String> out, String label, List<String> values) {
        if (values.isEmpty()) {
            out.add("  " + pad(label) + "(none)");
            return;
        }
        boolean first = true;
        for (String v : values) {
            out.add("  " + pad(first ? label : "") + v);
            first = false;
        }
    }

    private static String pad(String label) {
        return label.length() >= LABEL ? label + " " : label + " ".repeat(LABEL - label.length());
    }

    private static String size(long bytes) {
        return bytes < 0L ? "size unknown" : bytes + " bytes";
    }

    private static String stamp(long millis) {
        return LOCAL.format(Instant.ofEpochMilli(millis));
    }

    private ReportBody() {
    }
}
