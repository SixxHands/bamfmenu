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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** (the enable bit is the user's) + Q1 (the kill list) + the dupe guard. */
class DisabledAndQuarantineTest {

    @TempDir
    Path dir;

    private Reconciler rec() {
        return new Reconciler(dir, Reconciler.Side.CLIENT, List.of());
    }

    private static FileEntry optionalMod(String path, byte[] bytes, boolean installDisabled, List<String> modids) {
        return Fx.entry(path, bytes, FileEntry.Side.CLIENT, FileEntry.Policy.MANAGED,
                installDisabled, "optional", FileEntry.OnRemove.ORPHAN, modids);
    }

    /** Assert integrity refusals in the console output, since showing a clear message to the player
     *  is the point of these checks. */
    private interface Body {
        void run() throws IOException;
    }

    private static String captureOut(Body body) throws IOException {
        java.io.PrintStream real = System.out;
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(buf, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setOut(real);
        }
        return buf.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void installStateDisabledLandsAsDisabledFile() throws IOException {
        byte[] jar = Fx.jar("optmod");
        FileEntry e = optionalMod("mods/opt.jar", jar, true, List.of("optmod"));
        Ledger led = new Ledger();
        Manifest m = Fx.manifest("v1", List.of(), e);
        rec().apply(rec().plan(m, led), m, led, Fx.fetcher());
        assertTrue(Files.exists(dir.resolve("mods/opt.jar.disabled")));
        assertFalse(Files.exists(dir.resolve("mods/opt.jar")));
        assertTrue(led.files.get("mods/opt.jar").disabled);
    }

    @Test
    void userDisabledModStaysDisabledThroughAnUpdate_andReenableIsRespected() throws IOException {
        byte[] v1 = Fx.jar("thing");
        byte[] v2 = Fx.jar("thing", "thing_extra");
        FileEntry e1 = optionalMod("mods/thing.jar", v1, false, List.of("thing"));
        Ledger led = new Ledger();
        Manifest m1 = Fx.manifest("v1", List.of(), e1);
        rec().apply(rec().plan(m1, led), m1, led, Fx.fetcher());
        // the user turns it off
        Files.move(dir.resolve("mods/thing.jar"), dir.resolve("mods/thing.jar.disabled"));
        FileEntry e2 = optionalMod("mods/thing.jar", v2, false, List.of("thing", "thing_extra"));
        Manifest m2 = Fx.manifest("v2", List.of(), e2);
        rec().apply(rec().plan(m2, led), m2, led, Fx.fetcher());
        assertTrue(Files.exists(dir.resolve("mods/thing.jar.disabled")));
        assertFalse(Files.exists(dir.resolve("mods/thing.jar")));
        assertArrayEquals(v2, Files.readAllBytes(dir.resolve("mods/thing.jar.disabled")));
        // the user turns it back on: next sync leaves the enabled name alone
        Files.move(dir.resolve("mods/thing.jar.disabled"), dir.resolve("mods/thing.jar"));
        SyncPlan p3 = rec().plan(m2, led);
        rec().apply(p3, m2, led, Fx.fetcher());
        assertTrue(Files.exists(dir.resolve("mods/thing.jar")));
        assertFalse(led.files.get("mods/thing.jar").disabled);
    }

    @Test
    void disabledBitFollowsTheModAcrossAVersionRename() throws IOException {
        byte[] oldJar = Fx.jar("iris");
        byte[] newJar = Fx.jar("iris");
        FileEntry oldE = optionalMod("mods/iris-1.7.jar", oldJar, false, List.of("iris"));
        Ledger led = new Ledger();
        Manifest m1 = Fx.manifest("v1", List.of(), oldE);
        rec().apply(rec().plan(m1, led), m1, led, Fx.fetcher());
        Files.move(dir.resolve("mods/iris-1.7.jar"), dir.resolve("mods/iris-1.7.jar.disabled")); // user: off
        FileEntry newE = optionalMod("mods/iris-1.8.jar", newJar, false, List.of("iris"));
        Manifest m2 = Fx.manifest("v2", List.of(), newE);
        rec().apply(rec().plan(m2, led), m2, led, Fx.fetcher());
        assertTrue(Files.exists(dir.resolve("mods/iris-1.8.jar.disabled")));
        assertFalse(Files.exists(dir.resolve("mods/iris-1.8.jar")));
        assertFalse(Files.exists(dir.resolve("mods/iris-1.7.jar.disabled")));
    }

    @Test
    void q1_quarantinesKilledModidOnce_reenableRespectedForever() throws IOException {
        Fx.write(dir, "mods/chipped-old.jar", Fx.jar("chipped")); // pre-updater leftover, untracked
        Ledger led = new Ledger();
        Manifest m = Fx.manifest("v1", List.of("chipped"));
        SyncPlan p = rec().plan(m, led);
        assertEquals(1, p.actions.stream().filter(a -> a.kind() == Kind.QUARANTINE).count());
        rec().apply(p, m, led, Fx.fetcher());
        assertTrue(Files.exists(dir.resolve("mods/chipped-old.jar.disabled")));
        assertFalse(Files.exists(dir.resolve("mods/chipped-old.jar")));
        assertTrue(led.files.get("mods/chipped-old.jar").quarantined);
        // If the user re-enables it manually, Q1 must NOT fire again.
        Files.move(dir.resolve("mods/chipped-old.jar.disabled"), dir.resolve("mods/chipped-old.jar"));
        assertTrue(rec().plan(m, led).actions.isEmpty());
    }

    /**
     * Q1 quarantine must rename in place rather than copy through staging and delete, so an AV scan can't leave
     * mods/ missing the jar. Asserted on the journal ops directly.
     */
    @Test
    void quarantineNeverDeletesWithoutPlacing() throws IOException {
        Fx.write(dir, "mods/chipped-old.jar", Fx.jar("chipped"));
        Ledger led = new Ledger();
        Manifest m = Fx.manifest("v1", List.of("chipped"));
        List<Journal.Op> ops = new ArrayList<>();
        // the journal is written and then deleted inside one apply(), and applying() fires between the two
        Reconciler.Pulse peek = () -> {
            try {
                ops.addAll(Journal.load(Journal.pathIn(dir)).ops);
            } catch (IOException unreadable) {
                throw new AssertionError(unreadable);
            }
        };
        rec().apply(rec().plan(m, led), m, led, Fx.fetcher(), peek, false);

        assertEquals(1, ops.size());
        Journal.Op op = ops.get(0);
        assertEquals("rename", op.kind());
        assertEquals("mods/chipped-old.jar", op.from());
        assertEquals("mods/chipped-old.jar.disabled", op.to());
        assertTrue(ops.stream().noneMatch(o -> o.kind().equals("delete")));

        assertTrue(Files.exists(dir.resolve("mods/chipped-old.jar.disabled")));
        assertFalse(Files.exists(dir.resolve("mods/chipped-old.jar")));
        assertTrue(led.files.get("mods/chipped-old.jar").quarantined);
    }

    /**
     * A jar listed before downloads can vanish (AV or user delete) before apply() hashes it; handle the missing
     * file cleanly instead of failing the entire sync.
     */
    @Test
    void quarantineOfAVanishedJar_doesNotAbortTheSync() throws IOException {
        byte[] managed = Fx.jar("keeper");
        FileEntry e = optionalMod("mods/keep.jar", managed, false, List.of("keeper"));
        Fx.write(dir, "mods/chipped-old.jar", Fx.jar("chipped"));
        Ledger led = new Ledger();
        Manifest m = Fx.manifest("v1", List.of("chipped"), e);
        SyncPlan p = rec().plan(m, led);
        assertEquals(1, p.actions.stream().filter(a -> a.kind() == Kind.QUARANTINE).count());
        Files.delete(dir.resolve("mods/chipped-old.jar")); // the gap between planning and applying

        rec().apply(p, m, led, Fx.fetcher());
        assertArrayEquals(managed, Files.readAllBytes(dir.resolve("mods/keep.jar")));
        assertNull(led.files.get("mods/chipped-old.jar"));
    }

    /**
     * Best-effort .bak creation runs before journaling and must not throw if blocked (simulated via a directory).
     */
    @Test
    void heldBak_doesNotCostTheSync() throws IOException {
        byte[] managed = Fx.jar("keeper");
        byte[] edit = "the player's edit".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FileEntry e = optionalMod("mods/keep.jar", managed, false, List.of("keeper"));
        Ledger led = new Ledger();
        led.installedVersion = "v1";
        led.files.put("config/gone.toml", new Ledger.Entry("stale-sha"));
        Fx.write(dir, "config/gone.toml", edit);
        Files.createDirectories(dir.resolve("config/gone.toml.bak-v1/wedge"));
        Manifest m = Fx.manifest("v2", List.of(), e);

        rec().apply(rec().plan(m, led), m, led, Fx.fetcher());
        assertArrayEquals(managed, Files.readAllBytes(dir.resolve("mods/keep.jar")));
    }

    /**
     * For rule-E deletes and rule-C overwrites, if creating the .bak fails we must defer the op and keep
     * installed_version instead of destroying the player's edit without a backup.
     */
    @Test
    void heldBak_defersTheDeleteInsteadOfLosingTheEdit() throws IOException {
        byte[] edit = "the player's edit".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Ledger led = new Ledger();
        led.installedVersion = "v1";
        led.files.put("config/gone.toml", new Ledger.Entry("stale-sha"));
        Fx.write(dir, "config/gone.toml", edit);
        Files.createDirectories(dir.resolve("config/gone.toml.bak-v1/wedge"));
        Manifest m = Fx.manifest("v2", List.of());

        rec().apply(rec().plan(m, led), m, led, Fx.fetcher());
        assertArrayEquals(edit, Files.readAllBytes(dir.resolve("config/gone.toml")));
        assertNotNull(led.files.get("config/gone.toml"));
        assertEquals("v1", led.installedVersion);
    }

    /** The same veto on side, where the ledger is the damage: a skipped REPLACE_BAK leaves a regular
     *  file at the target name, so landed answers true and the ledger would record the NEW sha over the
     *  player's OLD bytes. This rule then stops hashing that path and the divergence is permanent. */
    @Test
    void heldBak_defersRuleC_andTheLedgerNeverClaimsTheNewSha() throws IOException {
        byte[] v1 = "one".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] v2 = "two".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] edit = "the player's edit".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FileEntry old = Fx.entry("config/a.toml", v1, FileEntry.Side.CLIENT);
        FileEntry neu = Fx.entry("config/a.toml", v2, FileEntry.Side.CLIENT);
        Ledger led = new Ledger();
        led.installedVersion = "v1";
        led.files.put("config/a.toml", new Ledger.Entry(old.sha256()));
        Fx.write(dir, "config/a.toml", edit); // edited AND the pack changed the file: this rule
        Files.createDirectories(dir.resolve("config/a.toml.bak-v1/wedge"));
        Manifest m = Fx.manifest("v2", List.of(), neu);

        assertEquals(Kind.REPLACE_BAK, rec().plan(m, led).actions.stream()
                .filter(a -> a.path().equals("config/a.toml")).findFirst().orElseThrow().kind());
        rec().apply(rec().plan(m, led), m, led, Fx.fetcher());
        assertArrayEquals(edit, Files.readAllBytes(dir.resolve("config/a.toml")));
        assertEquals(old.sha256(), led.files.get("config/a.toml").sha256);
        assertEquals("v1", led.installedVersion);
    }

    /**
     * Only log a .disabled-twin refusal for jars on the Q1 kill list, so unrelated crash debris won't spam logs.
     */
    @Test
    void twinRefusal_isSilentForAJarThatWasNeverACandidate() throws IOException {
        Fx.write(dir, "mods/chipped-old.jar", Fx.jar("chipped")); // on the kill list, twin present
        Fx.write(dir, "mods/chipped-old.jar.disabled", "parked".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Fx.write(dir, "mods/someunrelated.jar", Fx.jar("someunrelated")); // declares nothing killed
        Fx.write(dir, "mods/someunrelated.jar.disabled", "theirs".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Ledger led = new Ledger();

        String out = captureOut(() -> rec().plan(Fx.manifest("v1", List.of("chipped")), led));

        assertTrue(out.contains("cannot quarantine mods/chipped-old.jar"), out);
        assertFalse(out.contains("someunrelated"));
    }

    /** The C1 landed() message is for managed files (ledger + old bytes). Untracked third-party
     *  jars have neither, so a skipped quarantine shouldn't fall through and print a contradicting line. */
    @Test
    void skippedQuarantine_doesNotPrintTheManagedLedgerLine() throws IOException {
        Fx.write(dir, "mods/chipped-old.jar", Fx.jar("chipped"));
        Ledger led = new Ledger();
        Manifest m = Fx.manifest("v1", List.of("chipped"));
        SyncPlan p = rec().plan(m, led);
        Files.delete(dir.resolve("mods/chipped-old.jar")); // the gap between planning and applying

        String out = captureOut(() -> rec().apply(p, m, led, Fx.fetcher()));

        assertTrue(out.contains("cannot quarantine mods/chipped-old.jar, unreadable right now"), out);
        assertFalse(out.contains("ledger left on the old bytes"));
    }

    /**
     * If a .disabled twin appears during download, replay refuses to overwrite it. Don't mark quarantined=true
     * just because the destination exists, or Q1 skips the still-enabled jar forever.
     */
    @Test
    void aRefusedQuarantineRename_writesNoLedgerRecord() throws IOException {
        byte[] parked = "the user's own parked copy".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Fx.write(dir, "mods/chipped-old.jar", Fx.jar("chipped"));
        Ledger led = new Ledger();
        Manifest m = Fx.manifest("v1", List.of("chipped"));
        SyncPlan p = rec().plan(m, led);
        assertEquals(1, p.actions.stream().filter(a -> a.kind() == Kind.QUARANTINE).count());
        Fx.write(dir, "mods/chipped-old.jar.disabled", parked); // the gap between planning and applying

        rec().apply(p, m, led, Fx.fetcher());

        assertNull(led.files.get("mods/chipped-old.jar"));
        assertTrue(Files.exists(dir.resolve("mods/chipped-old.jar")));
        assertArrayEquals(parked, Files.readAllBytes(dir.resolve("mods/chipped-old.jar.disabled")));
        assertFalse(Files.exists(Journal.pathIn(dir)));
    }

    /** Replay uses REPLACE_EXISTING, so don't quarantine onto a .disabled name the user already
     *  has, or we'd overwrite their file. Matches KickGuard behavior. */
    @Test
    void quarantineSkipsWhenADisabledTwinExists() throws IOException {
        byte[] parked = "the user's own parked copy".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Fx.write(dir, "mods/chipped-old.jar", Fx.jar("chipped"));
        Fx.write(dir, "mods/chipped-old.jar.disabled", parked);
        Ledger led = new Ledger();
        Manifest m = Fx.manifest("v1", List.of("chipped"));
        assertTrue(rec().plan(m, led).actions.isEmpty());
        assertArrayEquals(parked, Files.readAllBytes(dir.resolve("mods/chipped-old.jar.disabled")));
        assertTrue(Files.exists(dir.resolve("mods/chipped-old.jar")));
    }

    /** the ledger records on_remove at INSTALL time, because this rule needs it years later when the
     *  manifest that declared it is gone. A seed committed flagless makes a rollback a silent delete, and
     *  this rule emits no action for an existing seed, so nothing ever backfills the flag. */
    @Test
    void seedOnce_orphanFlag_outlivesTheManifestThatDeclaredIt() throws IOException {
        byte[] v1 = "seeded".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] edit = "the player's edit".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FileEntry seed = Fx.entry("config/seeded.toml", v1, FileEntry.Side.CLIENT,
                FileEntry.Policy.SEED_ONCE, false, null, FileEntry.OnRemove.ORPHAN, List.of());
        Ledger led = new Ledger();
        Manifest m1 = Fx.manifest("v1", List.of(), seed);
        rec().apply(rec().plan(m1, led), m1, led, Fx.fetcher());
        assertTrue(led.files.get("config/seeded.toml").orphanOnRemove);

        Fx.write(dir, "config/seeded.toml", edit);
        Manifest dropped = Fx.manifest("v2", List.of()); // a rollback to a release that predates the seed
        SyncPlan p = rec().plan(dropped, led);
        assertEquals(Kind.ORPHAN, p.actions.stream().filter(a -> a.path().equals("config/seeded.toml"))
                .findFirst().orElseThrow().kind());
        rec().apply(p, dropped, led, Fx.fetcher());
        assertArrayEquals(edit, Files.readAllBytes(dir.resolve("config/seeded.toml")));
    }

    /** Rule-E deletes back up non-mod files with a .bak, but skip removed jars to avoid .bak litter. */
    @Test
    void ruleEDelete_leavesABakForANonModFile() throws IOException {
        byte[] edit = "the player's edit".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Ledger led = new Ledger();
        led.installedVersion = "v1";
        led.files.put("config/gone.toml", new Ledger.Entry("stale-sha"));
        led.files.put("mods/gone.jar", new Ledger.Entry("stale-sha"));
        Fx.write(dir, "config/gone.toml", edit);
        Fx.write(dir, "mods/gone.jar", Fx.jar("gonemod"));
        Manifest empty = Fx.manifest("v2", List.of());
        rec().apply(rec().plan(empty, led), empty, led, Fx.fetcher());
        assertFalse(Files.exists(dir.resolve("config/gone.toml")));
        assertArrayEquals(edit, Files.readAllBytes(dir.resolve("config/gone.toml.bak-v1")));
        assertFalse(Files.exists(dir.resolve("mods/gone.jar.bak-v1")));
    }

    @Test
    void dupeModidAbortsBeforeAnyMove_namesTheUntrackedCulprit() throws IOException {
        byte[] managed = Fx.jar("samemod");
        byte[] stray = Fx.jar("samemod");
        FileEntry e = Fx.entry("mods/managed.jar", managed, FileEntry.Side.CLIENT, FileEntry.Policy.MANAGED,
                false, null, FileEntry.OnRemove.DELETE, List.of("samemod"));
        Fx.write(dir, "mods/stray-copy.jar", stray); // a player's hand-dropped duplicate
        Ledger led = new Ledger();
        Manifest m = Fx.manifest("v1", List.of(), e);
        SyncPlan p = rec().plan(m, led);
        Reconciler.DupeModidAbort abort = assertThrows(Reconciler.DupeModidAbort.class,
                () -> rec().apply(p, m, led, Fx.fetcher()));
        assertTrue(abort.getMessage().contains("stray-copy.jar"), abort.getMessage());
        assertTrue(abort.getMessage().contains("untracked"));
        assertFalse(Files.exists(dir.resolve("mods/managed.jar")));
        assertTrue(led.files.isEmpty());
    }
}
