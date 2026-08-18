package com.retro.bamfos.updater.engine.reconcile;

import com.retro.bamfos.updater.engine.Fx;
import com.retro.bamfos.updater.engine.ledger.Ledger;
import com.retro.bamfos.updater.engine.manifest.FileEntry;
import com.retro.bamfos.updater.engine.manifest.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An install without a ledger builds one from disk so renamed mods can be cleaned up and
 *  dupeGuard stops aborting. Jars not shipped by the pack must be skipped so player mods aren't
 *  deleted. */
class LedgerBootstrapTest {

    @TempDir
    Path dir;

    private Reconciler rec() {
        return new Reconciler(dir, Reconciler.Side.CLIENT, List.of());
    }

    private static FileEntry mod(String path, byte[] bytes, List<String> modids) {
        return Fx.entry(path, bytes, FileEntry.Side.CLIENT, FileEntry.Policy.MANAGED,
                false, null, FileEntry.OnRemove.DELETE, modids);
    }

    private static FileEntry optionalMod(String path, byte[] bytes, boolean installDisabled, List<String> modids) {
        return Fx.entry(path, bytes, FileEntry.Side.CLIENT, FileEntry.Policy.MANAGED,
                installDisabled, "optional", FileEntry.OnRemove.ORPHAN, modids);
    }

    private Ledger bootstrap(Manifest m) throws IOException {
        return LedgerBootstrap.ensure(dir, new Ledger(), m, Reconciler.Side.CLIENT, new PinMatcher(List.of()));
    }

    /** Reproduces the bug: without a ledger, the old jar isn't removed and two jars end up providing foo. */
    @Test
    void noLedger_renamedMod_wouldAbortWithoutBootstrap() throws IOException {
        byte[] incoming = Fx.jar("foo");
        Fx.write(dir, "mods/foo-1.0.jar", Fx.jar("foo"));
        FileEntry e = mod("mods/foo-2.0.jar", incoming, List.of("foo"));
        Manifest m = Fx.manifest("v2", List.of(), e);
        assertThrows(Reconciler.DupeModidAbort.class,
                () -> rec().apply(rec().plan(m, new Ledger()), m, new Ledger(), Fx.fetcher()));
    }

    @Test
    void noLedger_renamedMod_convergesWithBootstrap() throws IOException {
        byte[] incoming = Fx.jar("foo");
        Fx.write(dir, "mods/foo-1.0.jar", Fx.jar("foo")); // old jar on disk, untracked, no ledger
        FileEntry e = mod("mods/foo-2.0.jar", incoming, List.of("foo"));
        Manifest m = Fx.manifest("v2", List.of(), e);

        Ledger led = bootstrap(m);
        rec().apply(rec().plan(m, led), m, led, Fx.fetcher());
        assertTrue(Files.exists(dir.resolve("mods/foo-2.0.jar")));
        assertFalse(Files.exists(dir.resolve("mods/foo-1.0.jar")));
    }

    /** The gate. A pack mod on disk gets a row and is retired; a player's own mod gets no row and this rule,
     *  which only walks rows, never touches it. */
    @Test
    void noLedger_playerOwnMod_isNeverEaten() throws IOException {
        Fx.write(dir, "mods/coolmod.jar", Fx.jar("coolmod"));       // user-installed mod (not shipped by the pack)
        Fx.write(dir, "mods/packmod-old.jar", Fx.jar("packmod"));   // a pack mod, old version, on disk
        FileEntry e = mod("mods/packmod-new.jar", Fx.jar("packmod"), List.of("packmod"));
        Manifest m = Fx.manifest("v2", List.of(), e);

        Ledger led = bootstrap(m);
        assertTrue(led.files.containsKey("mods/packmod-old.jar"));
        assertFalse(led.files.containsKey("mods/coolmod.jar"));

        rec().apply(rec().plan(m, led), m, led, Fx.fetcher());
        assertTrue(Files.exists(dir.resolve("mods/coolmod.jar")));
        assertTrue(Files.exists(dir.resolve("mods/packmod-new.jar")));
        assertFalse(Files.exists(dir.resolve("mods/packmod-old.jar")));
    }

    /** If a user jar bundles one of our libs alongside a mod we don't ship, don't claim or delete
     *  it; leave it alone and let the duplicate guard report it instead. */
    @Test
    void playerModBundlingAPackLibrary_isNeverEaten() throws IOException {
        Fx.write(dir, "mods/mymod-2.0.jar", Fx.jar("mymod", "collective")); // player's, bundles our lib
        Fx.write(dir, "mods/collective-7.8.jar", Fx.jar("collective"));     // ours, old version
        FileEntry lib = mod("mods/collective-7.9.jar", Fx.jar("collective"), List.of("collective"));
        Manifest m = Fx.manifest("v2", List.of(), lib);

        Ledger led = bootstrap(m);
        assertFalse(led.files.containsKey("mods/mymod-2.0.jar"));
        assertTrue(led.files.containsKey("mods/collective-7.8.jar"));

        Reconciler.DupeModidAbort stop = assertThrows(Reconciler.DupeModidAbort.class,
                () -> rec().apply(rec().plan(m, led), m, led, Fx.fetcher()));
        assertTrue(stop.getMessage().contains("mymod-2.0.jar"));
        assertTrue(Files.exists(dir.resolve("mods/mymod-2.0.jar")));
    }

    /** We bundle derelict inside bamfcat, so a standalone derelict jar duplicates a bundled mod at
     *  the wrong version. Remove it instead of disabling it so it can't be turned back on. */
    @Test
    void aStandaloneCopyOfSomethingWeBundle_isReclaimed() throws IOException {
        Fx.write(dir, "mods/derelict.jar", Fx.jar("derelict"));                // duplicate of what we carry
        Fx.write(dir, "mods/bamfcat-1.0.jar", Fx.jar("bamfcat", "derelict"));  // ours, old version
        FileEntry ours = mod("mods/bamfcat-1.1.jar", Fx.jar("bamfcat", "derelict"),
                List.of("bamfcat", "derelict"));
        Manifest m = Fx.manifest("v2", List.of(), ours);

        Ledger led = bootstrap(m);
        assertTrue(led.files.containsKey("mods/derelict.jar"));
        assertTrue(led.files.containsKey("mods/bamfcat-1.0.jar"));

        rec().apply(rec().plan(m, led), m, led, Fx.fetcher());
        assertFalse(Files.exists(dir.resolve("mods/derelict.jar")));
        assertTrue(Files.exists(dir.resolve("mods/bamfcat-1.1.jar")));
        assertFalse(Files.exists(dir.resolve("mods/bamfcat-1.0.jar")));
    }

    /** A renamed copy of one of our jars only declares our modids, so claim and clean it up
     *  instead of failing on it every sync. */
    @Test
    void ourJarRenamedByHand_isStillOurs() throws IOException {
        Fx.write(dir, "mods/i-renamed-this.jar", Fx.jar("packmod"));
        FileEntry e = mod("mods/packmod-2.0.jar", Fx.jar("packmod"), List.of("packmod"));
        Manifest m = Fx.manifest("v2", List.of(), e);

        Ledger led = bootstrap(m);
        assertTrue(led.files.containsKey("mods/i-renamed-this.jar"));

        rec().apply(rec().plan(m, led), m, led, Fx.fetcher());
        assertFalse(Files.exists(dir.resolve("mods/i-renamed-this.jar")));
        assertTrue(Files.exists(dir.resolve("mods/packmod-2.0.jar")));
    }

    /** Repair uses the same ledger logic, so it leaves untracked user mods alone too. */
    @Test
    void repair_neverTouchesAnUntrackedPlayerMod() throws IOException {
        Fx.write(dir, "mods/coolmod.jar", Fx.jar("coolmod"));
        Fx.write(dir, "mods/other-thing-1.2.jar", Fx.jar("otherthing", "extrabit"));
        byte[] ours = Fx.jar("packmod");
        FileEntry e = mod("mods/packmod.jar", ours, List.of("packmod"));
        Fx.write(dir, "mods/packmod.jar", ours);
        Manifest m = Fx.manifest("v2", List.of(), e);

        Ledger led = bootstrap(m);
        rec().apply(rec().plan(m, led, true), m, led, Fx.fetcher()); // repair=true
        assertTrue(Files.exists(dir.resolve("mods/coolmod.jar")));
        assertTrue(Files.exists(dir.resolve("mods/other-thing-1.2.jar")));
        assertTrue(Files.exists(dir.resolve("mods/packmod.jar")));
    }

    @Test
    void noLedger_disabledOptional_staysDisabled() throws IOException {
        byte[] bytes = Fx.jar("opt");
        Fx.write(dir, "mods/opt.jar.disabled", bytes); // player had it off
        FileEntry e = optionalMod("mods/opt.jar", bytes, true, List.of("opt"));
        Manifest m = Fx.manifest("v2", List.of(), e);

        Ledger led = bootstrap(m);
        assertTrue(led.files.get("mods/opt.jar").disabled);

        rec().apply(rec().plan(m, led), m, led, Fx.fetcher());
        assertTrue(Files.exists(dir.resolve("mods/opt.jar.disabled")));
        assertFalse(Files.exists(dir.resolve("mods/opt.jar")));
    }

    /** Derived ledgers leave installed_version empty until apply finishes, and don't write to disk
     *  before plan so an aborted run doesn't leave a partial ledger behind. */
    @Test
    void derivedLedger_claimsNoVersion_untilApplyCommitsIt() throws IOException {
        byte[] bytes = Fx.jar("packmod");
        Fx.write(dir, "mods/packmod.jar", bytes);
        FileEntry e = mod("mods/packmod.jar", bytes, List.of("packmod"));
        Manifest m = Fx.manifest("v2", List.of(), e);

        Ledger led = bootstrap(m);
        assertEquals("", led.installedVersion);
        assertFalse(Files.exists(Ledger.pathIn(dir)));

        rec().apply(rec().plan(m, led), m, led, Fx.fetcher());
        assertTrue(Files.exists(Ledger.pathIn(dir)));
        assertEquals("v2", Ledger.load(Ledger.pathIn(dir)).installedVersion);
    }

    @Test
    void emptyBox_derivesNothing_andWritesNoLedger() throws IOException {
        FileEntry e = mod("mods/packmod.jar", Fx.jar("packmod"), List.of("packmod"));
        Manifest m = Fx.manifest("v2", List.of(), e);
        Ledger loaded = new Ledger();
        Ledger out = LedgerBootstrap.ensure(dir, loaded, m, Reconciler.Side.CLIENT, new PinMatcher(List.of()));
        assertSame(loaded, out);
        assertFalse(Files.exists(Ledger.pathIn(dir)));
    }

    @Test
    void existingLedger_isNeverReDerived() throws IOException {
        Ledger loaded = new Ledger();
        loaded.files.put("mods/x.jar", new Ledger.Entry("deadbeef"));
        FileEntry e = mod("mods/packmod.jar", Fx.jar("packmod"), List.of("packmod"));
        Manifest m = Fx.manifest("v2", List.of(), e);
        Ledger out = LedgerBootstrap.ensure(dir, loaded, m, Reconciler.Side.CLIENT, new PinMatcher(List.of()));
        assertSame(loaded, out);
    }
}
