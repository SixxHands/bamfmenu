package com.retro.bamfos.updater.engine.reconcile;

import com.retro.bamfos.updater.engine.Fx;
import com.retro.bamfos.updater.engine.journal.Journal;
import com.retro.bamfos.updater.engine.ledger.Ledger;
import com.retro.bamfos.updater.engine.manifest.FileEntry;
import com.retro.bamfos.updater.engine.manifest.Manifest;
import com.retro.bamfos.updater.engine.util.HashUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Disk checks during apply: converged sweeps delete .dl partials while keeping factory reset state,
 * syncs refuse upfront when disk space isn't enough, and stuck-file messages only report existing files.
 */
class StagingAndSpaceTest {

    @TempDir
    Path dir;

    private Reconciler rec() {
        return new Reconciler(dir, Reconciler.Side.CLIENT, List.of());
    }

    /** HttpFetcher stages downloads to a .dl file first, so a failed download leaves both .part and .part.dl.
     *  The sweep used to only match *.part, leaving .part.dl behind to trip the reset space check. */
    @Test
    void theConvergedSweepTakesDlPartials_andSparesAResetsResumeState() throws IOException {
        Path staging = dir.resolve(".bamfos-updater/staging");
        Files.createDirectories(staging);
        String sha = "a".repeat(64); // isResetPart expects a valid SHA format
        Files.write(staging.resolve("dead0.part"), new byte[]{1});
        Files.write(staging.resolve("dead1.part.dl"), new byte[]{2}); // leftover resume file from an aborted download
        Files.write(staging.resolve(sha + "-fr0.part"), new byte[]{3});
        Files.write(staging.resolve(sha + "-fr0.part.dl"), new byte[]{4});

        byte[] cfg = "cfg".getBytes(StandardCharsets.UTF_8);
        Manifest m = Fx.manifest("vT", List.of(), Fx.entry("config/a.toml", cfg, FileEntry.Side.CLIENT));
        Ledger led = new Ledger();
        rec().apply(rec().plan(m, led), m, led, Fx.fetcher()); // converges, which triggers the cleanup sweep

        assertFalse(Files.exists(staging.resolve("dead0.part")));
        assertFalse(Files.exists(staging.resolve("dead1.part.dl")));
        assertTrue(Files.exists(staging.resolve(sha + "-fr0.part")));
        assertTrue(Files.exists(staging.resolve(sha + "-fr0.part.dl")));
    }

    /** Check free space against the manifest size before downloading during normal sync, not just
     *  factory reset. If there isn't enough room, fail cleanly with a message before fetching or
     *  changing anything. */
    @Test
    void aSyncThatCannotFitItsDownloads_refusesBeforeTheFirstFetch() throws IOException {
        byte[] bytes = "small real bytes".getBytes(StandardCharsets.UTF_8);
        FileEntry entry = new FileEntry("mods/huge.jar", HashUtils.sha256(bytes), 1L << 50,
                FileEntry.Side.CLIENT, FileEntry.Policy.MANAGED, FileEntry.Clazz.HOT,
                new FileEntry.Source(FileEntry.Source.Type.RELEASE_ASSET, "mods/huge.jar", null, null, null, null),
                List.of(), false, null, FileEntry.OnRemove.DELETE, null); // a petabyte on paper
        Manifest m = Fx.manifest("vT", List.of(), entry);
        Ledger led = new Ledger();
        int[] fetches = {0};
        Reconciler.Fetcher never = (e, dest) -> fetches[0]++;

        IOException refusal = assertThrows(IOException.class,
                () -> rec().apply(rec().plan(m, led), m, led, never));

        assertTrue(refusal.getMessage().contains("not enough free space"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("Nothing was changed"), refusal.getMessage());
        assertEquals(0, fetches[0]);
        assertFalse(Files.exists(Journal.pathIn(dir)));
    }

    /** The two lines that used to promise "old bytes" unconditionally. A stuck REPLACE really does keep
     *  them now (replay swaps atomically), but a stuck fresh INSTALL never had any, and a player told his
     *  mod stays on its old bytes goes looking for a file that never existed. */
    @Test
    void theStuckLinesOnlyClaimOldBytesWhenAStatBacksThemUp() throws IOException {
        Fx.write(dir, "mods/there.jar", "old".getBytes(StandardCharsets.UTF_8));

        assertTrue(Reconciler.gaveUpLine(dir, new Journal.Op("move", "s", "mods/there.jar", ""))
                .contains("old bytes"));
        assertFalse(Reconciler.gaveUpLine(dir, new Journal.Op("move", "s", "mods/gone.jar", ""))
                .contains("old bytes"));

        Reconciler rec = rec();
        assertTrue(rec.notRecorded(new SyncPlan.Action(SyncPlan.Kind.REPLACE, "mods/there.jar", null, false))
                .contains("old bytes"));
        assertFalse(rec.notRecorded(new SyncPlan.Action(SyncPlan.Kind.INSTALL, "mods/gone.jar", null, false))
                .contains("old bytes"));
    }
}
