package com.retro.bamfos.updater.engine.reconcile;

import com.retro.bamfos.updater.engine.Fx;
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
