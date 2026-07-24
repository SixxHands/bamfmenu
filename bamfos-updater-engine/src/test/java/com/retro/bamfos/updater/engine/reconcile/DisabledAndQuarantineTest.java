package com.retro.bamfos.updater.engine.reconcile;

import com.retro.bamfos.updater.engine.Fx;
import com.retro.bamfos.updater.engine.ledger.Ledger;
import com.retro.bamfos.updater.engine.manifest.FileEntry;
import com.retro.bamfos.updater.engine.manifest.Manifest;
import com.retro.bamfos.updater.engine.reconcile.SyncPlan.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
