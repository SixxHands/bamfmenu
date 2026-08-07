package com.retro.bamfos.updater.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/* Tracks core generation strikes and rejections over .bamfos-updater/core/ files (shared with stub and
 * engine). Two strikes deletes the bad core jar and records it in core-rejected so we don't re-install it. */
final class CoreHealth {

    /** Written by the stub before calling enter() and deleted once an engine loads:
     *  `v1 TAB running TAB pid TAB pidStartMillis TAB skipped`. Only append new fields at the end
     *  so older stubs can still read fields 0-3. */
    private static final String MARK_FILE = "core-unproven";

    /** Tracks strike counts (`gen TAB count`) before rejection. Kept separate from REJECTED_FILE
     *  so the engine doesn't need to know the strike threshold. */
    private static final String STRIKES_FILE = "core-strikes";

    /** The refusal list, `gen TAB rejectedAtMillis`. Read by the ENGINE too, because a generation refused here must
     *  never be written back to disk by the cargo that delivered it. */
    private static final String REJECTED_FILE = "core-rejected";

    /** Reserved floor jar (embedded in the stub): never struck and never pruned so fallback always exists. */
    private static final String FLOOR = "core-0000.jar";

    /** Strict core-NNNN.jar name check. Always validate before resolving paths so a tampered mark
     *  file can't traverse out of the core folder and delete other files. Four digits also keeps
     *  lexical sort in numeric order. */
    private static final Pattern GENERATION = Pattern.compile("core-\\d{4}\\.jar");

    /** Require two strikes before rejecting a core, since an Alt+F4 or power cut can leave a mark behind. */
    static final int STRIKES_TO_REJECT = 2;

    /** Cap strike/rejection history at five entries since we only keep three core jars on disk anyway. */
    private static final int REJECT_MAX = 5;

    /** Returns null if there's no mark, or an empty array if the mark exists but can't be read
     *  (still treated as armed, matching the stub and EngineGuard). */
    static String[] readMark(Path dir) {
        Path f = dir.resolve(MARK_FILE);
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            return Files.readString(f, StandardCharsets.UTF_8).trim().split("\t");
        } catch (IOException | RuntimeException unreadable) {
            return new String[0];
        }
    }

    /**
     * True only if this process armed the mark (matching pid and start time) so we never clear a mark
     * from another instance. Start time 0 matches 0 when the OS omits it, avoiding false mismatches.
     */
    static boolean armedHere(String[] mark) {
        if (mark == null || mark.length < 4 || !mark[0].trim().equals("v1")) {
            return false;
        }
        try {
            ProcessHandle self = ProcessHandle.current();
            long started = self.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
            return Long.parseLong(mark[2].trim()) == self.pid() && Long.parseLong(mark[3].trim()) == started;
        } catch (RuntimeException junk) {
            return false;
        }
    }

    /** Field 1, the generation the stub handed control to. */
    static String running(String[] mark) {
        return mark != null && mark.length >= 2 ? mark[1].trim() : "";
    }

    /** Field 4: the skipped generation name, or "" if none was skipped. When the stub had to run
     *  the blamed jar anyway, it writes "-" so the fallback core doesn't strike and delete itself. */
    static String skipped(String[] mark) {
        if (mark == null || mark.length < 5) {
            return "";
        }
        String gen = mark[4].trim();
        return gen.equals("-") ? "" : gen;
    }

    /** Clears the mark after a successful load. Only call from the process that armed it. */
    static void clearMark(Path dir) {
        try {
            Files.deleteIfExists(dir.resolve(MARK_FILE));
        } catch (IOException | RuntimeException stuck) {
            // If we can't delete the mark, the next boot will fall back one generation and clear it there.
            System.out.println("[updater] core mark could not be cleared: " + stuck);
        }
    }

    /**
     * Records a strike against a generation and deletes its jar on the second strike to stop flapping.
     * Validates the filename against core-NNNN.jar before resolving paths.
     */
    static boolean strike(Path dir, String gen) {
        if (gen == null || !GENERATION.matcher(gen).matches() || gen.equals(FLOOR)) {
            return false;
        }
        if (firstFields(dir.resolve(REJECTED_FILE)).contains(gen)) {
            // Already rejected; still retry the delete in case AV or folder permissions blocked it before.
            remove(dir.resolve(gen));
            return true;
        }
        try {
            Map<String, Integer> strikes = counted(dir.resolve(STRIKES_FILE));
            int count = strikes.getOrDefault(gen, 0) + 1;
            if (count < STRIKES_TO_REJECT) {
                strikes.put(gen, count);
                writeCapped(dir.resolve(STRIKES_FILE), lines(strikes));
                System.out.println("[updater] core " + gen + " did not prove itself, strike " + count
                        + " of " + STRIKES_TO_REJECT);
                return false;
            }
            strikes.remove(gen); // its counter is spent, the refusal list owns it now
            writeCapped(dir.resolve(STRIKES_FILE), lines(strikes));
            List<String> burned = new ArrayList<>(allLines(dir.resolve(REJECTED_FILE)));
            burned.add(gen + "\t" + System.currentTimeMillis());
            writeCapped(dir.resolve(REJECTED_FILE), burned);
            System.out.println("[updater] core " + gen + " failed twice, it is refused");
            remove(dir.resolve(gen));
            return true;
        } catch (IOException | RuntimeException disk) {
            System.out.println("[updater] could not record the failed core, "
                    + "it may be offered again next boot: " + disk);
            return false;
        }
    }

    /**
     * Deletes a rejected core jar in a separate try block so a locked file doesn't stop us from recording
     * the rejection. If AV or permissions block deletion, the next boot still skips it.
     */
    private static void remove(Path jar) {
        try {
            Files.deleteIfExists(jar);
        } catch (IOException | RuntimeException held) {
            System.out.println("[updater] refused core " + jar.getFileName() + " could not be deleted, "
                    + "it will be offered and declined again instead: " + held);
        }
    }

    /**
     * Keeps the two newest generations, the embedded fallback, and the running jar, pruning older ones.
     * The running jar is pinned explicitly in case the stub skipped broken jars newer than it.
     */
    static void prune(Path dir, String running) {
        List<Path> generations;
        try (Stream<Path> list = Files.list(dir)) {
            generations = list.filter(p -> GENERATION.matcher(name(p)).matches())
                    .sorted(Comparator.comparing(CoreHealth::name).reversed())
                    .toList();
        } catch (IOException | RuntimeException none) {
            return; // Missing dir or permissions: nothing to prune, don't fail the boot.
        }
        for (int i = 2; i < generations.size(); i++) {
            String gen = name(generations.get(i));
            if (gen.equals(FLOOR) || gen.equals(running)) {
                continue;
            }
            try {
                Files.deleteIfExists(generations.get(i));
            } catch (IOException | RuntimeException held) {
                // if AV or a sibling locks an old generation jar, skip deletion rather than failing boot
            }
        }
    }

    /* Duplicated from EngineGuard so its private layout and on-disk state stay byte-identical
       for older shims. Keep both copies in sync if this changes. */

    /** Reads the first tab-separated field (the generation name) from each line. */
    private static List<String> firstFields(Path f) {
        List<String> out = new ArrayList<>();
        for (String line : allLines(f)) {
            String gen = line.split("\t")[0].trim();
            if (!gen.isEmpty()) {
                out.add(gen);
            }
        }
        return out;
    }

    /** Unreadable reads as empty, because a missing memory must never be the reason a boot stops. */
    private static List<String> allLines(Path f) {
        try {
            return Files.isRegularFile(f) ? Files.readAllLines(f, StandardCharsets.UTF_8) : List.of();
        } catch (IOException | RuntimeException unreadable) {
            return List.of();
        }
    }

    /** Parses gen -> strike count from the strikes file in insertion order so trimming drops the oldest. */
    private static Map<String, Integer> counted(Path f) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String line : allLines(f)) {
            String[] parts = line.split("\t");
            if (parts.length >= 2 && !parts[0].trim().isEmpty()) {
                try {
                    out.put(parts[0].trim(), Integer.parseInt(parts[1].trim()));
                } catch (NumberFormatException junk) {
                    out.put(parts[0].trim(), 1); // a mangled count still means it failed once
                }
            }
        }
        return out;
    }

    private static List<String> lines(Map<String, Integer> strikes) {
        return strikes.entrySet().stream().map(e -> e.getKey() + "\t" + e.getValue()).toList();
    }

    private static void writeCapped(Path f, List<String> lines) throws IOException {
        List<String> kept = new ArrayList<>(lines);
        while (kept.size() > REJECT_MAX) {
            kept.remove(0);
        }
        Files.createDirectories(f.getParent());
        Files.write(f, kept, StandardCharsets.UTF_8);
    }

    private static String name(Path p) {
        return p.getFileName().toString();
    }

    private CoreHealth() {
    }
}
