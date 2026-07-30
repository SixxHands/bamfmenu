package com.retro.bamfos.updater.engine.factory;

import com.retro.bamfos.updater.engine.journal.Journal;
import com.retro.bamfos.updater.engine.manifest.FileEntry;
import com.retro.bamfos.updater.engine.manifest.Manifest;
import com.retro.bamfos.updater.engine.reconcile.Reconciler;
import com.retro.bamfos.updater.engine.util.HashUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

// Stages a full pack download and writes a single journal to wipe and replace on next boot (nothing gets deleted until the journal saves).
public final class FactoryReset {

    /** Summary returned by arm(). `reused` tracks staged parts kept from an earlier interrupted attempt
     *  (already subtracted from the free-space check) so callers and tests can observe resume progress. */
    public record Armed(int deletes, int places, int reused, long payloadBytes, String tag,
                        DiskSpace.Room room) {
    }

    /** Precondition failures that block arming a reset. Returned as readable status messages instead of
     *  throwing exceptions so the UI can show a clear reason to the player. */
    public static class Refused extends Exception {
        public final String why;

        Refused(String why) {
            super(why);
            this.why = why;
        }
    }

    private FactoryReset() {
    }

    /** Lists every manifest entry for this side to install cleanly (including seeded configs). Unlike a
     *  normal reconcile, replay deletes files first so every entry is placed unconditionally. */
    static List<FileEntry> toPlace(Manifest manifest, Reconciler.Side side) {
        List<FileEntry> out = new ArrayList<>();
        for (FileEntry e : manifest.files()) {
            if (!reaches(e.side(), side) || WipeSet.KEEPERS.contains(e.path())) {
                continue; // reset never overwrites updater files (see WipeSet.KEEPERS)
            }
            out.add(e);
        }
        return out;
    }

    private static boolean reaches(FileEntry.Side entry, Reconciler.Side box) {
        return entry == FileEntry.Side.BOTH
                || (box == Reconciler.Side.CLIENT && entry == FileEntry.Side.CLIENT)
                || (box == Reconciler.Side.SERVER && entry == FileEntry.Side.SERVER);
    }

    /** Where an entry lands, which for an optional shipped off is its .disabled twin. A reset restores the
     *  SHIPPED defaults, so a mod the player turned on comes back off and one they turned off comes back on. */
    static String target(FileEntry e) {
        return e.installDisabled() ? e.path() + ".disabled" : e.path();
    }

    /** Fetches missing parts and writes the reset journal, or throws {@link Refused} without touching files.
     *  @param onFile non-null progress callback invoked before each fetch */
    public static Armed arm(Path dir, Manifest manifest, Reconciler.Side side, Reconciler.Fetcher fetcher,
                            String tag, Progress onFile) throws IOException, Refused {
        WipeSet.Plan wipe = WipeSet.enumerate(dir, side);
        if (!wipe.safe()) {
            // Abort if the wipe walk included a protected file; that means a filter rule above is broken and we
            // shouldn't skip it and continue the reset.
            throw new Refused("the wipe would have removed files the updater needs (" + wipe.refused()
                    + "), so nothing was changed. This is a bug, not something you did.");
        }

        List<FileEntry> place = toPlace(manifest, side);
        if (place.isEmpty()) {
            throw new Refused("that release lists no files for this install, so there is nothing to reset to.");
        }
        Path staging = dir.resolve(".bamfos-updater").resolve("staging");
        Files.createDirectories(staging);

        // Derive deterministic part names from (sha, index) so retries reuse staged files and duplicate-sha
        // entries at different paths get distinct sources (the first move consumes its source).
        List<Path> parts = new ArrayList<>(place.size());
        for (int i = 0; i < place.size(); i++) {
            parts.add(staging.resolve(place.get(i).sha256() + "-fr" + i + ".part"));
        }

        // Reuse verified parts already in staging from an interrupted reset so retries don't re-download
        // or fail the free-space check over their own leftovers. Hash each part before trusting it.
        boolean[] have = new boolean[place.size()];
        long haveBytes = 0;
        int reused = 0;
        for (int i = 0; i < place.size(); i++) {
            if (Files.isRegularFile(parts.get(i))
                    && HashUtils.sha256(parts.get(i)).equals(place.get(i).sha256())) {
                have[i] = true;
                haveBytes += place.get(i).size();
                reused++;
            }
        }
        sweepStrays(staging, parts);

        long payload = place.stream().mapToLong(FileEntry::size).sum();
        // the check is about what still has to COME DOWN, because what is already staged is already paid for
        DiskSpace.Room room = DiskSpace.check(dir, payload - haveBytes);
        if (room.blocks()) {
            throw new Refused("not enough free space: a reset needs about " + DiskSpace.mb(room.needBytes())
                    + " free to download the rest of the pack before it removes the old one, and this drive "
                    + "has " + DiskSpace.mb(room.freeBytes()) + ". Nothing was changed.");
        }
        if (reused > 0) {
            System.out.println("[updater] resuming a reset: " + reused + " of " + place.size()
                    + " files were already downloaded and still verify");
        }

        List<Journal.Op> moves = new ArrayList<>();
        Set<String> targets = new LinkedHashSet<>();
        for (int i = 0; i < place.size(); i++) {
            FileEntry e = place.get(i);
            String to = target(e);
            Path part = parts.get(i);
            onFile.at(to, i + 1, place.size());
            if (!have[i]) {
                fetcher.fetch(e, part);
                if (!HashUtils.sha256(part).equals(e.sha256())) {
                    // verify before writing the journal so a failure leaves the existing install untouched
                    throw new IOException("staged bytes for " + e.path() + " failed hash verification");
                }
            }
            moves.add(new Journal.Op("move", rel(dir, part), to, e.sha256()));
            targets.add(to);
        }

        Journal journal = new Journal();
        journal.targetVersion = manifest.pack().version();
        // Queue deletes before moves so targets are cleared in order. Exempt move targets (replaced in place)
        // and move sources (WipeSet walks staging/, so deleting sources would wipe our own staged payload
        // before replay). Filter by exact source set so it stays safe if staging naming changes.
        Set<String> sources = new HashSet<>();
        for (Journal.Op m : moves) {
            sources.add(m.from());
        }
        for (String gone : wipe.delete()) {
            if (!targets.contains(gone) && !sources.contains(gone)) {
                journal.ops.add(new Journal.Op("delete", "", gone, ""));
            }
        }
        journal.ops.addAll(moves);

        assertNoKeeperDeleted(journal);
        assertNoSourceDeleted(journal);
        journal.save(Journal.pathIn(dir));
        return new Armed(journal.ops.size() - moves.size(), moves.size(), reused, payload, tag, room);
    }

    /** Final check on the finished journal ensuring no keeper file (config, token, shim, etc.) is
     *  deleted. Intentionally independent of the list-building logic above. */
    static void assertNoKeeperDeleted(Journal journal) throws Refused {
        Set<String> bad = new HashSet<>();
        for (Journal.Op op : journal.ops) {
            if (op.kind().equals("delete") && WipeSet.KEEPERS.contains(op.to())) {
                bad.add(op.to());
            }
        }
        if (!bad.isEmpty()) {
            throw new Refused("refusing to arm a reset that would delete " + bad
                    + ", which the updater needs to run again. Nothing was changed.");
        }
    }

    /** Independent safety check ensuring no delete op removes a move source in the same journal.
     *  Deletes run before moves, so deleting a source would cause the move to be permanently abandoned. */
    static void assertNoSourceDeleted(Journal journal) throws Refused {
        Set<String> deletes = new HashSet<>();
        for (Journal.Op op : journal.ops) {
            if (op.kind().equals("delete")) {
                deletes.add(op.to());
            }
        }
        Set<String> bad = new HashSet<>();
        for (Journal.Op op : journal.ops) {
            if (!op.kind().equals("delete") && deletes.contains(op.from())) {
                bad.add(op.from());
            }
        }
        if (!bad.isEmpty()) {
            throw new Refused("refusing to arm a reset that would delete " + bad.size() + " of the files it "
                    + "then needs to install from (" + bad.stream().findFirst().orElse("") + " and so on). "
                    + "Nothing was changed. This is a bug, not something you did.");
        }
    }

    /**
     * Deletes reset parts from an EARLIER release, which are pure dead weight: their names carry a sha this
     * manifest never asks for, so nothing will ever reuse them, and until they go they hold the space the
     * free-space check is about to demand.
     * Only ever touches `-fr` parts this arm is not using. An ordinary staged sync's parts live in the same
     * folder and its journal still needs them, so sweeping by "everything in staging" would break a pending
     * sync to tidy up after a reset. Best-effort throughout: a file we cannot delete is a file the journal
     * removes later anyway.
     */
    private static void sweepStrays(Path staging, List<Path> keep) {
        Set<String> mine = new HashSet<>();
        for (Path p : keep) {
            mine.add(p.getFileName().toString());
        }
        try (Stream<Path> tree = Files.list(staging)) {
            tree.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".part") && n.contains("-fr") && !mine.contains(n);
                    })
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException held) {
                            // nothing to do and nothing lost: the journal lists staging for deletion too
                        }
                    });
        } catch (IOException unreadable) {
            // no staging dir, or we cannot list it. Either way there is nothing to reclaim
        }
    }

    /** Progress for the fetch half. The delete and place halves are reported by step 0 on the next boot,
     *  where the boot terminal is already drawing a bar. */
    public interface Progress {
        void at(String path, int done, int total);
    }

    private static String rel(Path dir, Path file) {
        return dir.relativize(file).toString().replace('\\', '/');
    }
}
