package com.retro.bamfos.updater.engine.reconcile;

import com.retro.bamfos.updater.engine.Fx;
import com.retro.bamfos.updater.engine.journal.Journal;
import com.retro.bamfos.updater.engine.ledger.Ledger;
import com.retro.bamfos.updater.engine.manifest.FileEntry;
import com.retro.bamfos.updater.engine.manifest.Manifest;
import com.retro.bamfos.updater.engine.ping.ServersDat;
import com.retro.bamfos.updater.engine.util.HashUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** two reconciler additions: staged mode and the merge-servers rule-D extension. */
class M6ReconcilerTest {

    @TempDir
    Path dir;

    private static FileEntry serversEntry(byte[] seedBytes) {
        Fx.CONTENT.put("servers.dat@" + HashUtils.sha256(seedBytes), seedBytes);
        return new FileEntry("servers.dat", HashUtils.sha256(seedBytes), seedBytes.length,
                FileEntry.Side.CLIENT, FileEntry.Policy.SEED_ONCE, FileEntry.Clazz.HOT,
                new FileEntry.Source(FileEntry.Source.Type.RELEASE_ASSET, "servers.dat", null, null, null, null),
                List.of(), false, null, FileEntry.OnRemove.DELETE, "servers", List.of());
    }

    @Test
    void stagedApply_journalsButTouchesNothing_thenStepZeroConverges() throws IOException {
        byte[] oldBytes = "old cfg".getBytes(StandardCharsets.UTF_8);
        byte[] newBytes = "new cfg".getBytes(StandardCharsets.UTF_8);
        Fx.write(dir, "config/a.toml", oldBytes);
        Ledger ledger = new Ledger();
        ledger.files.put("config/a.toml", new Ledger.Entry(HashUtils.sha256(oldBytes)));
        ledger.installedVersion = "vOLD";
        ledger.save(Ledger.pathIn(dir));

        Manifest m = Fx.manifest("vNEW", List.of(), Fx.entry("config/a.toml", newBytes, FileEntry.Side.CLIENT));
        Reconciler rec = new Reconciler(dir, Reconciler.Side.CLIENT, List.of());
        SyncPlan plan = rec.plan(m, ledger);
        rec.apply(plan, m, ledger, Fx.fetcher(), Reconciler.Pulse.NONE, true);

        assertArrayEquals(oldBytes, Files.readAllBytes(dir.resolve("config/a.toml")));
        assertTrue(Files.exists(Journal.pathIn(dir)));
        assertEquals("vOLD", Ledger.load(Ledger.pathIn(dir)).installedVersion);

        // Next boot step 0: replay staged bytes, then rule A2 adopts them.
        Journal j = Journal.load(Journal.pathIn(dir));
        assertTrue(j.replay(dir).allLanded());
        assertArrayEquals(newBytes, Files.readAllBytes(dir.resolve("config/a.toml")));
        Ledger fresh = Ledger.load(Ledger.pathIn(dir));
        SyncPlan next = new Reconciler(dir, Reconciler.Side.CLIENT, List.of()).plan(m, fresh);
        assertTrue(next.actions.stream().allMatch(a -> a.kind() == SyncPlan.Kind.ADOPT));
    }

    /**
     * A staged SEED op must refuse to overwrite a file the player created before next boot's step 0 runs,
     * letting the next sync re-plan safely (keeping user configs or merging servers.dat).
     */
    @Test
    void aStagedSeed_refusesToEatAFileTheSessionCreated() throws IOException {
        byte[] seed = Fx.serversDat(Fx.serverIp());
        byte[] theirs = Fx.serversDat("their-own-server");
        FileEntry entry = serversEntry(seed);
        Manifest m = Fx.manifest("vT", List.of(), entry);
        Reconciler rec = new Reconciler(dir, Reconciler.Side.CLIENT, List.of());
        Ledger ledger = new Ledger();

        SyncPlan p = rec.plan(m, ledger);
        assertEquals(SyncPlan.Kind.SEED, p.actions.get(0).kind());
        rec.apply(p, m, ledger, Fx.fetcher(), Reconciler.Pulse.NONE, true); // staged: journal only
        Files.write(dir.resolve("servers.dat"), theirs); // the session the player spends adding a server

        Journal j = Journal.load(Journal.pathIn(dir));
        assertEquals("seed", j.ops.get(0).kind());
        Journal.Result r = j.replay(dir);
        assertEquals(1, r.abandoned().size());
        assertTrue(r.retryable().isEmpty());
        assertArrayEquals(theirs, Files.readAllBytes(dir.resolve("servers.dat")));
        Files.delete(Journal.pathIn(dir)); // what step 0 does when a journal has nothing retryable

        // Next boot keeps the user's file and merges our entry into it via rule D
        // (detailed merge cases are tested below).
        Ledger fresh = Ledger.load(Ledger.pathIn(dir));
        SyncPlan next = rec.plan(m, fresh);
        assertEquals(SyncPlan.Kind.MERGE_SERVERS, next.actions.get(0).kind());
        rec.apply(next, m, fresh, Fx.fetcher());
        assertEquals(Fx.serverIp(), ServersDat.entryIp(dir.resolve("servers.dat"), "BAMFOS").orElseThrow());
    }

    @Test
    void mergeServers_rowsFire_andLedgerRecordsMergeName() throws IOException {
        byte[] seed = Fx.serversDat(Fx.serverIp());
        FileEntry entry = serversEntry(seed);
        Manifest m = Fx.manifest("vT", List.of(), entry);
        Reconciler rec = new Reconciler(dir, Reconciler.Side.CLIENT, List.of());

        // fresh install: plain SEED records the merge name in the same commit, avoiding a follow-up
        // MERGE_SERVERS backfill that would re-download the data zip
        Ledger ledger = new Ledger();
        SyncPlan p1 = rec.plan(m, ledger);
        assertEquals(SyncPlan.Kind.SEED, p1.actions.get(0).kind());
        rec.apply(p1, m, ledger, Fx.fetcher());
        assertArrayEquals(seed, Files.readAllBytes(dir.resolve("servers.dat")));
        assertEquals("BAMFOS", ledger.files.get("servers.dat").mergeName);
        assertTrue(rec.plan(m, ledger).actions.isEmpty());

        // Older ledger without merge_name: a single MERGE_SERVERS op migrates it to the new format.
        ledger.files.get("servers.dat").mergeName = null;
        SyncPlan p2 = rec.plan(m, ledger);
        assertEquals(SyncPlan.Kind.MERGE_SERVERS, p2.actions.get(0).kind());
        rec.apply(p2, m, ledger, Fx.fetcher());
        assertEquals("BAMFOS", ledger.files.get("servers.dat").mergeName);
        assertTrue(ledger.files.get("servers.dat").seedOnce);

        // steady state: nothing to do
        assertTrue(rec.plan(m, ledger).actions.isEmpty());

        // the player deleted our entry -> restored, without a seed re-download being the trigger
        Files.write(dir.resolve("servers.dat"), Fx.serversDat()); // empty server list
        SyncPlan p3 = rec.plan(m, ledger);
        assertEquals(SyncPlan.Kind.MERGE_SERVERS, p3.actions.get(0).kind());
        rec.apply(p3, m, ledger, Fx.fetcher());
        assertEquals(Fx.serverIp(), ServersDat.entryIp(dir.resolve("servers.dat"), "BAMFOS").orElseThrow());

        // server IP changes -> new seed bytes -> refresh while preserving player entries
        byte[] seed2 = Fx.serversDat("99.99.99.99");
        FileEntry entry2 = serversEntry(seed2);
        Manifest m2 = Fx.manifest("vT2", List.of(), entry2);
        SyncPlan p4 = rec.plan(m2, ledger);
        assertEquals(SyncPlan.Kind.MERGE_SERVERS, p4.actions.get(0).kind());
        rec.apply(p4, m2, ledger, Fx.fetcher());
        assertEquals("99.99.99.99", ServersDat.entryIp(dir.resolve("servers.dat"), "BAMFOS").orElseThrow());
    }
}
