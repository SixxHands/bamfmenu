package com.retro.bamfos.updater.engine.reconcile;

import com.retro.bamfos.updater.engine.Fx;
import com.retro.bamfos.updater.engine.journal.Journal;
import com.retro.bamfos.updater.engine.ledger.Ledger;
import com.retro.bamfos.updater.engine.manifest.FileEntry;
import com.retro.bamfos.updater.engine.manifest.Manifest;
import com.retro.bamfos.updater.engine.reconcile.SyncPlan.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** One test per table row, plus the crash-convergence and sovereignty cases. */
class ReconcilerTableTest {

    @TempDir
    Path dir;

    private static final byte[] V1 = "one".getBytes(StandardCharsets.UTF_8);
    private static final byte[] V2 = "two".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EDIT = "user edit".getBytes(StandardCharsets.UTF_8);

    private Reconciler rec() {
        return new Reconciler(dir, Reconciler.Side.CLIENT, List.of());
    }

    private Kind only(SyncPlan p, String path) {
        var acts = p.actions.stream().filter(a -> a.path().equals(path)).toList();
        assertEquals(1, acts.size(), String.valueOf(path) + " / " + String.valueOf(acts));
        return acts.get(0).kind();
    }

    /**
     * Never record the target sha in the ledger when a move fails (simulated with a non-empty directory), or
     * the rule stops hashing the path and leaves the old file stuck forever.
     */
    @Test
    void lockedTarget_ledgerNeverClaimsTheNewBytes() throws IOException {
        FileEntry e = Fx.entry("mods/x.jar", V2, FileEntry.Side.CLIENT);
        Ledger led = new Ledger();
        led.installedVersion = "v1";
        Files.createDirectories(dir.resolve("mods/x.jar/wedge"));
        Files.write(dir.resolve("mods/x.jar/wedge/f"), V1);

        Manifest m = Fx.manifest("v2", List.of(), e);
        SyncPlan p = rec().plan(m, led);
        rec().apply(p, m, led, Fx.fetcher());

        assertNull(led.files.get("mods/x.jar"));
        assertEquals("v1", led.installedVersion);
        assertTrue(Files.exists(Journal.pathIn(dir)));

        // Unblock the file: the saved journal applies the staged bytes offline with no network calls.
        Files.delete(dir.resolve("mods/x.jar/wedge/f"));
        Files.delete(dir.resolve("mods/x.jar/wedge"));
        Files.delete(dir.resolve("mods/x.jar"));
        assertTrue(Journal.load(Journal.pathIn(dir)).replay(dir).allLanded());
        assertArrayEquals(V2, Files.readAllBytes(dir.resolve("mods/x.jar")));
    }

    /**
     * If a staged .part vanishes between journal.save and replay, replay marks it abandoned and blocks the
     * ledger update. Globs the staged file via the Pulse seam so staging filename changes don't break this.
     */
    @Test
    void stagedSourceEatenBeforeReplay_ledgerNeverClaimsTheNewBytes() throws IOException {
        FileEntry e = Fx.entry("mods/x.jar", V2, FileEntry.Side.CLIENT);
        Ledger led = new Ledger();
        led.installedVersion = "v1";
        Manifest m = Fx.manifest("v2", List.of(), e);
        SyncPlan p = rec().plan(m, led);

        rec().apply(p, m, led, Fx.fetcher(), eatStaging(), false);

        assertFalse(Files.isRegularFile(dir.resolve("mods/x.jar")));
        assertNull(led.files.get("mods/x.jar"));
        assertEquals("v1", led.installedVersion);
        assertFalse(Files.exists(Journal.pathIn(dir)));
    }

    /**
     * For REPLACE, an existence check passes even when the move fails, so replay's report must gate the ledger
     * for every op kind (not just DELETE). Uses a missing staged source as a portable failure trigger.
     */
    @Test
    void replaceOverAnExistingName_ledgerNeverClaimsBytesThatDidNotLand() throws IOException {
        FileEntry old = Fx.entry("config/a.toml", V1, FileEntry.Side.CLIENT);
        FileEntry neu = Fx.entry("config/a.toml", V2, FileEntry.Side.CLIENT);
        Ledger led = new Ledger();
        led.installedVersion = "v1";
        led.files.put("config/a.toml", new Ledger.Entry(old.sha256()));
        Fx.write(dir, "config/a.toml", V1); // disk matches the ledger, so the pack's new bytes are this rule
        Manifest m = Fx.manifest("v2", List.of(), neu);

        SyncPlan p = rec().plan(m, led);
        assertEquals(Kind.REPLACE, only(p, "config/a.toml"));
        rec().apply(p, m, led, Fx.fetcher(), eatStaging(), false);

        assertArrayEquals(V1, Files.readAllBytes(dir.resolve("config/a.toml")));
        assertEquals(old.sha256(), led.files.get("config/a.toml").sha256);
        assertEquals("v1", led.installedVersion);
    }

    /** Deletes all staged .part files between journal.save and replay to simulate antivirus removing
     *  a download. Uses a glob so renaming staging files later won't break these tests. */
    private Reconciler.Pulse eatStaging() {
        return () -> {
            try (var ds = Files.newDirectoryStream(dir.resolve(".bamfos-updater/staging"), "*.part")) {
                for (Path part : ds) {
                    Files.delete(part);
                }
            } catch (IOException gone) {
                throw new AssertionError("nothing staged to eat, the test would prove nothing", gone);
            }
        };
    }

    /**
     * Carry forward stuck ops left by step 0 instead of overwriting the journal, so cleanup ops (like a stale
     * .disabled twin) aren't lost forever.
     */
    @Test
    void aKeptJournalsOpsSurviveTheNextApply_insteadOfBeingOverwritten() throws IOException {
        Fx.write(dir, "mods/leftover.jar.disabled", V1); // the debris a stuck delete op was meant to clear
        Journal kept = new Journal();
        kept.targetVersion = "v2";
        kept.ops.add(new Journal.Op("delete", "", "mods/leftover.jar.disabled", ""));
        kept.save(Journal.pathIn(dir));

        FileEntry e = Fx.entry("config/a.toml", V2, FileEntry.Side.CLIENT);
        Ledger led = new Ledger();
        Manifest m = Fx.manifest("v2", List.of(), e); // Same target version, so carrying over staged files isn't a rollback.
        rec().apply(rec().plan(m, led), m, led, Fx.fetcher());

        assertFalse(Files.exists(dir.resolve("mods/leftover.jar.disabled")));
        assertArrayEquals(V2, Files.readAllBytes(dir.resolve("config/a.toml")));
    }

    /**
     * On rollback, drop leftover ops from the newer version so they can't overwrite files that already match
     * the rolled-back manifest.
     */
    @Test
    void aLeftoverOpFromAnotherTargetVersion_isNotCarriedIntoARollback() throws IOException {
        Fx.write(dir, ".bamfos-updater/staging/newer.part", V2);
        Journal kept = new Journal();
        kept.targetVersion = "v3"; // the release that was partway through installing before the rollback
        kept.ops.add(new Journal.Op("move", ".bamfos-updater/staging/newer.part", "config/a.toml",
                com.retro.bamfos.updater.engine.util.HashUtils.sha256(V2)));
        kept.save(Journal.pathIn(dir));

        FileEntry e = Fx.entry("config/a.toml", V1, FileEntry.Side.CLIENT);
        Ledger led = new Ledger();
        led.files.put("config/a.toml", new Ledger.Entry(e.sha256())); // this rule: already what v2 wants
        Fx.write(dir, "config/a.toml", V1);
        Manifest rolledBack = Fx.manifest("v2", List.of(), e);
        rec().apply(rec().plan(rolledBack, led), rolledBack, led, Fx.fetcher());

        assertArrayEquals(V1, Files.readAllBytes(dir.resolve("config/a.toml")));
    }

    /** Drop carried ops for paths this plan leaves alone, so a stale op can't overwrite a player-edited config. */
    @Test
    void aLeftoverOpForAPathThisRunDeferred_isNotCarried() throws IOException {
        Fx.write(dir, ".bamfos-updater/staging/left.part", V2);
        Journal kept = new Journal();
        kept.targetVersion = "v2"; // same target, so nothing else drops this op
        kept.ops.add(new Journal.Op("move", ".bamfos-updater/staging/left.part", "config/a.toml",
                com.retro.bamfos.updater.engine.util.HashUtils.sha256(V2)));
        kept.save(Journal.pathIn(dir));

        FileEntry old = Fx.entry("config/a.toml", V1, FileEntry.Side.CLIENT);
        FileEntry neu = Fx.entry("config/a.toml", V2, FileEntry.Side.CLIENT);
        Ledger led = new Ledger();
        led.installedVersion = "v1";
        led.files.put("config/a.toml", new Ledger.Entry(old.sha256()));
        Fx.write(dir, "config/a.toml", EDIT);                                // edited AND the pack moved: this rule
        Files.createDirectories(dir.resolve("config/a.toml.bak-v1/wedge")); // AV mid-scan on the old .bak
        Manifest m = Fx.manifest("v2", List.of(), neu);
        assertEquals(Kind.REPLACE_BAK, only(rec().plan(m, led), "config/a.toml"));

        rec().apply(rec().plan(m, led), m, led, Fx.fetcher());

        assertArrayEquals(EDIT, Files.readAllBytes(dir.resolve("config/a.toml")));
        assertEquals(old.sha256(), led.files.get("config/a.toml").sha256);
    }

    /**
     * Prepend carried ops (addAll(0, ...)) so a carried delete runs before a fresh placement for the same path;
     * appending it would delete the file we just placed.
     */
    @Test
    void aCarriedOpRunsBEFOREThisPlansOpForTheSameName() throws IOException {
        Fx.write(dir, "mods/p.jar", V1);
        Journal kept = new Journal();
        kept.targetVersion = "v2";
        kept.ops.add(new Journal.Op("delete", "", "mods/p.jar", "")); // leftover companion file that a stuck op never cleaned up
        kept.save(Journal.pathIn(dir));

        FileEntry old = Fx.entry("mods/p.jar", V1, FileEntry.Side.CLIENT);
        FileEntry neu = Fx.entry("mods/p.jar", V2, FileEntry.Side.CLIENT);
        Ledger led = new Ledger();
        led.installedVersion = "v1";
        led.files.put("mods/p.jar", new Ledger.Entry(old.sha256()));
        Manifest m = Fx.manifest("v2", List.of(), neu);
        assertEquals(Kind.REPLACE, only(rec().plan(m, led), "mods/p.jar"));

        rec().apply(rec().plan(m, led), m, led, Fx.fetcher());

        assertArrayEquals(V2, Files.readAllBytes(dir.resolve("mods/p.jar")));
        assertEquals(neu.sha256(), led.files.get("mods/p.jar").sha256);
        assertEquals("v2", led.installedVersion);
    }

    /**
     * Deduplicate carried ops across .jar and .jar.disabled names when a player toggles a mod mid-carry, or
     * replay would write both twins to disk.
     */
    @Test
    void aKeptOpForTheOtherEnableName_isRecognisedAsThisPlansRow() throws IOException {
        Fx.write(dir, ".bamfos-updater/staging/left.part", V2);
        Journal kept = new Journal();
        kept.targetVersion = "v2";
        kept.ops.add(new Journal.Op("move", ".bamfos-updater/staging/left.part", "mods/p.jar.disabled",
                com.retro.bamfos.updater.engine.util.HashUtils.sha256(V2)));
        kept.save(Journal.pathIn(dir));

        FileEntry old = Fx.entry("mods/p.jar", V1, FileEntry.Side.CLIENT);
        FileEntry neu = Fx.entry("mods/p.jar", V2, FileEntry.Side.CLIENT);
        Ledger led = new Ledger();
        led.installedVersion = "v1";
        led.files.put("mods/p.jar", new Ledger.Entry(old.sha256()));
        Fx.write(dir, "mods/p.jar", V1); // User re-enabled it, so this plan targets the enabled filename.
        Manifest m = Fx.manifest("v2", List.of(), neu);

        rec().apply(rec().plan(m, led), m, led, Fx.fetcher());

        assertArrayEquals(V2, Files.readAllBytes(dir.resolve("mods/p.jar")));
        assertFalse(Files.exists(dir.resolve("mods/p.jar.disabled")));
    }

    /**
     * Sweep leftover staged .part files only after confirming no journal remains, so pending ops keep their bytes.
     */
    @Test
    void aDeferredRowsDeadDownload_doesNotSitInStagingForever() throws IOException {
        FileEntry old = Fx.entry("config/a.toml", V1, FileEntry.Side.CLIENT);
        FileEntry neu = Fx.entry("config/a.toml", V2, FileEntry.Side.CLIENT);
        Ledger led = new Ledger();
        led.installedVersion = "v1";
        led.files.put("config/a.toml", new Ledger.Entry(old.sha256()));
        Fx.write(dir, "config/a.toml", EDIT);                               // this rule, and the .bak is wedged,
        Files.createDirectories(dir.resolve("config/a.toml.bak-v1/wedge")); // so the download fails right away
        Manifest m = Fx.manifest("v2", List.of(), neu);

        rec().apply(rec().plan(m, led), m, led, Fx.fetcher());

        assertFalse(Files.exists(Journal.pathIn(dir)));
        try (var ds = Files.newDirectoryStream(dir.resolve(".bamfos-updater/staging"), "*.part")) {
            assertFalse(ds.iterator().hasNext());
        }
        assertArrayEquals(EDIT, Files.readAllBytes(dir.resolve("config/a.toml")));
    }

    @Test
    void ruleA_skipsWithoutHashingWhenLedgerMatchesManifest() throws IOException {
        FileEntry e = Fx.entry("config/a.toml", V1, FileEntry.Side.CLIENT);
        Ledger led = new Ledger();
        led.files.put("config/a.toml", new Ledger.Entry(e.sha256()));
        Fx.write(dir, "config/a.toml", EDIT); // user edited; pack unchanged -> survives
        SyncPlan p = rec().plan(Fx.manifest("v1", List.of(), e), led);
        assertTrue(p.actions.isEmpty());
        assertArrayEquals(EDIT, Files.readAllBytes(dir.resolve("config/a.toml")));
    }

    @Test
    void ruleAPrime_restoresDeletedManagedFile() throws IOException {
        FileEntry e = Fx.entry("mods/x.jar", V1, FileEntry.Side.CLIENT);
        Ledger led = new Ledger();
        led.files.put("mods/x.jar", new Ledger.Entry(e.sha256()));
        SyncPlan p = rec().plan(Fx.manifest("v1", List.of(), e), led);
        assertEquals(Kind.RESTORE, only(p, "mods/x.jar"));
        rec().apply(p, Fx.manifest("v1", List.of(), e), led, Fx.fetcher());
        assertArrayEquals(V1, Files.readAllBytes(dir.resolve("mods/x.jar")));
    }

    @Test
    void ruleA2_adoptsCorrectBytesWithoutDownloading() throws IOException {
        FileEntry e = Fx.entry("config/a.toml", V2, FileEntry.Side.CLIENT);
        Fx.write(dir, "config/a.toml", V2); // disk already right (crash-after-move, or fresh seed)
        Ledger led = new Ledger(); // no ledger entry at all
        SyncPlan p = rec().plan(Fx.manifest("v2", List.of(), e), led);
        assertEquals(Kind.ADOPT, only(p, "config/a.toml"));
        assertTrue(p.downloads().isEmpty());
        rec().apply(p, Fx.manifest("v2", List.of(), e), led, (en, d) -> fail("fetcher must not be called"));
        assertEquals(e.sha256(), led.files.get("config/a.toml").sha256);
        assertFalse(Files.exists(dir.resolve("config/a.toml.bak-unknown")));
    }

    @Test
    void ruleB_replacesUntouchedFile() throws IOException {
        FileEntry old = Fx.entry("config/a.toml", V1, FileEntry.Side.CLIENT);
        FileEntry neu = Fx.entry("config/a.toml", V2, FileEntry.Side.CLIENT);
        Ledger led = new Ledger();
        led.files.put("config/a.toml", new Ledger.Entry(old.sha256()));
        Fx.write(dir, "config/a.toml", V1);
        SyncPlan p = rec().plan(Fx.manifest("v2", List.of(), neu), led);
        assertEquals(Kind.REPLACE, only(p, "config/a.toml"));
        rec().apply(p, Fx.manifest("v2", List.of(), neu), led, Fx.fetcher());
        assertArrayEquals(V2, Files.readAllBytes(dir.resolve("config/a.toml")));
    }

    @Test
    void ruleC_packWinsButUserEditGetsBak() throws IOException {
        FileEntry old = Fx.entry("config/a.toml", V1, FileEntry.Side.CLIENT);
        FileEntry neu = Fx.entry("config/a.toml", V2, FileEntry.Side.CLIENT);
        Ledger led = new Ledger();
        led.installedVersion = "v1";
        led.files.put("config/a.toml", new Ledger.Entry(old.sha256()));
        Fx.write(dir, "config/a.toml", EDIT); // user edited AND pack changed
        SyncPlan p = rec().plan(Fx.manifest("v2", List.of(), neu), led);
        assertEquals(Kind.REPLACE_BAK, only(p, "config/a.toml"));
        rec().apply(p, Fx.manifest("v2", List.of(), neu), led, Fx.fetcher());
        assertArrayEquals(V2, Files.readAllBytes(dir.resolve("config/a.toml")));
        assertArrayEquals(EDIT, Files.readAllBytes(dir.resolve("config/a.toml.bak-v1")));
    }

    @Test
    void ruleD_seedInstallsOnceThenNeverTouches() throws IOException {
        FileEntry seed = Fx.entry("servers.dat", V1, FileEntry.Side.CLIENT, FileEntry.Policy.SEED_ONCE,
                false, null, FileEntry.OnRemove.DELETE, List.of());
        Ledger led = new Ledger();
        Manifest m = Fx.manifest("v1", List.of(), seed);
        SyncPlan p1 = rec().plan(m, led);
        assertEquals(Kind.SEED, only(p1, "servers.dat"));
        rec().apply(p1, m, led, Fx.fetcher());
        // three later syncs with a user edit in place: never touched, even when the pack bytes change
        Fx.write(dir, "servers.dat", EDIT);
        FileEntry seedV2 = Fx.entry("servers.dat", V2, FileEntry.Side.CLIENT, FileEntry.Policy.SEED_ONCE,
                false, null, FileEntry.OnRemove.DELETE, List.of());
        for (int i = 0; i < 3; i++) {
            SyncPlan p = rec().plan(Fx.manifest("v" + (2 + i), List.of(), seedV2), led);
            assertTrue(p.actions.isEmpty());
        }
        assertArrayEquals(EDIT, Files.readAllBytes(dir.resolve("servers.dat")));
    }

    @Test
    void ruleE_deletesOnlyLedgerPaths_orphanLeavesFile() throws IOException {
        Ledger led = new Ledger();
        led.files.put("mods/gone.jar", new Ledger.Entry("x"));
        Ledger.Entry orph = new Ledger.Entry("y");
        orph.orphanOnRemove = true;
        led.files.put("mods/kept.jar", orph);
        Fx.write(dir, "mods/gone.jar", V1);
        Fx.write(dir, "mods/kept.jar", V1);
        Fx.write(dir, "mods/friends-own.jar", V1); // rule F: on disk, in neither manifest nor ledger
        Manifest empty = Fx.manifest("v2", List.of());
        SyncPlan p = rec().plan(empty, led);
        assertEquals(Kind.DELETE, only(p, "mods/gone.jar"));
        assertEquals(Kind.ORPHAN, only(p, "mods/kept.jar"));
        assertTrue(p.actions.stream().noneMatch(a -> a.path().contains("friends-own")));
        rec().apply(p, empty, led, Fx.fetcher());
        assertFalse(Files.exists(dir.resolve("mods/gone.jar")));
        assertTrue(Files.exists(dir.resolve("mods/kept.jar")));
        assertTrue(Files.exists(dir.resolve("mods/friends-own.jar")));
        assertTrue(led.files.isEmpty());
    }

    @Test
    void sideChange_bothToClient_cleansTheServerCopy() throws IOException {
        FileEntry clientOnly = Fx.entry("mods/x.jar", V1, FileEntry.Side.CLIENT);
        Ledger led = new Ledger();
        led.files.put("mods/x.jar", new Ledger.Entry(clientOnly.sha256()));
        Fx.write(dir, "mods/x.jar", V1);
        Reconciler server = new Reconciler(dir, Reconciler.Side.SERVER, List.of());
        SyncPlan p = server.plan(Fx.manifest("v2", List.of(), clientOnly), led);
        assertEquals(Kind.DELETE, only(p, "mods/x.jar")); // not applicable to this side = absent
    }

    @Test
    void pins_maskManifestAndLedger() throws IOException {
        FileEntry e = Fx.entry("mods/dev.jar", V2, FileEntry.Side.CLIENT);
        Ledger led = new Ledger();
        led.files.put("mods/dev.jar", new Ledger.Entry("old-sha-not-matching"));
        Fx.write(dir, "mods/dev.jar", EDIT);
        Reconciler pinned = new Reconciler(dir, Reconciler.Side.CLIENT, List.of("mods/dev*.jar"));
        // in the manifest: masked (no replace). Absent from the manifest: this rule must ALSO not fire.
        assertTrue(pinned.plan(Fx.manifest("v2", List.of(), e), led).actions.isEmpty());
        assertTrue(pinned.plan(Fx.manifest("v2", List.of()), led).actions.isEmpty());
        assertArrayEquals(EDIT, Files.readAllBytes(dir.resolve("mods/dev.jar")));
    }

    @Test
    void crashAfterMovesBeforeLedger_convergesViaAdoptWithZeroDownloads() throws IOException {
        FileEntry neu = Fx.entry("config/a.toml", V2, FileEntry.Side.CLIENT);
        Ledger led = new Ledger(); // ledger write never happened
        led.files.put("config/a.toml", new Ledger.Entry("sha-of-v1-era"));
        Fx.write(dir, "config/a.toml", V2); // the move DID happen before the crash
        SyncPlan p = rec().plan(Fx.manifest("v2", List.of(), neu), led);
        assertEquals(Kind.ADOPT, only(p, "config/a.toml"));
        rec().apply(p, Fx.manifest("v2", List.of(), neu), led, (en, d) -> fail("no re-download on re-run"));
    }
}
