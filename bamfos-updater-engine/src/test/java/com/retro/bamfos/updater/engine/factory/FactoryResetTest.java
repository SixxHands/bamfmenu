package com.retro.bamfos.updater.engine.factory;

import com.retro.bamfos.updater.engine.journal.Journal;
import com.retro.bamfos.updater.engine.manifest.FileEntry;
import com.retro.bamfos.updater.engine.manifest.Manifest;
import com.retro.bamfos.updater.engine.reconcile.Reconciler;
import com.retro.bamfos.updater.engine.util.HashUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Factory reset arming invariants: nothing changes before the journal is written, deletes precede
 * places, keepers are never touched, and any failure leaves the install intact. */
class FactoryResetTest {

    @TempDir
    Path dir;

    private final Map<String, byte[]> remote = new java.util.HashMap<>();

    /** In-memory fetcher returning the fixture bytes for each manifest entry. */
    private Reconciler.Fetcher fetcher() {
        return (entry, dest) -> {
            byte[] body = remote.get(entry.path());
            if (body == null) {
                throw new IOException("no such asset: " + entry.path());
            }
            Files.createDirectories(dest.getParent());
            Files.write(dest, body);
        };
    }

    /** Fetcher that fails partway through to test fetch-first staging safety. */
    private Reconciler.Fetcher failsAfter(int n) {
        int[] seen = {0};
        return (entry, dest) -> {
            if (seen[0]++ >= n) {
                throw new IOException("the network went away");
            }
            Files.createDirectories(dest.getParent());
            Files.write(dest, remote.get(entry.path()));
        };
    }

    private FileEntry entry(String path, String body, FileEntry.Side side, boolean off) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        remote.put(path, bytes);
        return new FileEntry(path, HashUtils.sha256(bytes), bytes.length, side, FileEntry.Policy.MANAGED,
                FileEntry.Clazz.HOT,
                new FileEntry.Source(FileEntry.Source.Type.RELEASE_ASSET, path, "", "", "", ""),
                List.of(), off, "", FileEntry.OnRemove.DELETE, "");
    }

    private Manifest manifest(FileEntry... files) {
        return new Manifest(1, new Manifest.Pack("bamfos", "v0.2.5", "1.21.1", "21.1.235", 1, List.of(1L)),
                false, List.of(), List.of(files));
    }

    private void onDisk(String rel, String body) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, body);
    }

    @BeforeEach
    void install() throws IOException {
        onDisk("config/bcc-common.toml", "old");
        onDisk("config/theirs.toml", "hand edited");
        onDisk("mods/old.jar", "a mod the pack dropped");
        onDisk("options.txt", "fov:90");
        onDisk("saves/World/level.dat", "IRREPLACEABLE");
        onDisk("mods/bamfos-updater-boot.jar", "the shim");
        onDisk(".bamfos-updater/bamfos-updater.properties", "repo=o/r");
    }

    private static final FactoryReset.Progress QUIET = (p, d, t) -> {
    };

    @Test
    void theJournalDeletesTheOldInstallAndPlacesTheNewOneInOneFile() throws Exception {
        Manifest m = manifest(
                entry("config/bcc-common.toml", "v0.2.5", FileEntry.Side.BOTH, false),
                entry("mods/create.jar", "create", FileEntry.Side.BOTH, false));

        FactoryReset.Armed armed = FactoryReset.arm(dir, m, Reconciler.Side.CLIENT, fetcher(), "v0.2.5", QUIET);

        assertEquals(2, armed.places());
        Journal j = Journal.load(Journal.pathIn(dir));
        assertEquals(armed.deletes() + armed.places(), j.ops.size());

        List<String> kinds = j.ops.stream().map(Journal.Op::kind).toList();
        assertTrue(kinds.lastIndexOf("delete") < kinds.indexOf("move"), String.valueOf(kinds));
    }

    /** Retrying an interrupted reset must not journal deletes for freshly staged move sources
     * (WipeSet scans staging/ before re-fetching). Verified end to end. */
    @Test
    void aResetRetriedAfterAFailedOneDoesNotDeleteItsOwnStagedFiles() throws Exception {
        Manifest m = manifest(
                entry("config/bcc-common.toml", "v0.2.5", FileEntry.Side.BOTH, false),
                entry("mods/create.jar", "create", FileEntry.Side.BOTH, false),
                entry("kubejs/server_scripts/a.js", "script", FileEntry.Side.BOTH, false));

        // First attempt fails after staging one file; existing install must remain untouched.
        assertThrows(IOException.class,
                () -> FactoryReset.arm(dir, m, Reconciler.Side.CLIENT, failsAfter(1), "v0.2.5", QUIET));
        assertFalse(Files.exists(Journal.pathIn(dir)));
        try (var s = Files.list(dir.resolve(".bamfos-updater/staging"))) {
            assertTrue(s.findAny().isPresent());
        }

        // Second attempt on the same release, reusing the same staged paths listed in the wipe set.
        FactoryReset.arm(dir, m, Reconciler.Side.CLIENT, fetcher(), "v0.2.5", QUIET);
        Journal j = Journal.load(Journal.pathIn(dir));

        java.util.Set<String> deleted = new java.util.HashSet<>();
        for (Journal.Op op : j.ops) {
            if (op.kind().equals("delete")) {
                deleted.add(op.to());
            }
        }
        for (Journal.Op op : j.ops) {
            if (!op.kind().equals("delete")) {
                assertFalse(deleted.contains(op.from()), String.valueOf(op.from()));
            }
        }

        // Replaying the journal should produce the complete target install.
        Journal.Result r = j.replay(dir);
        assertTrue(r.allLanded(), String.valueOf(r.abandoned().size()) + " / " + String.valueOf(r.retryable().size()));
        assertEquals("v0.2.5", Files.readString(dir.resolve("config/bcc-common.toml")));
        assertEquals("create", Files.readString(dir.resolve("mods/create.jar")));
        assertEquals("script", Files.readString(dir.resolve("kubejs/server_scripts/a.js")));
        assertEquals("IRREPLACEABLE", Files.readString(dir.resolve("saves/World/level.dat")));
    }

    /** bus.log and journal.json must stay out of the wipe set; deleting bus.log mid-replay broke
     * BootBus.emit and froze the boot terminal with the BIOS locked. */
    @Test
    void theResetNeverDeletesTheFeedOrTheJournalItIsRunningOn() throws Exception {
        onDisk(".bamfos-updater/bus.log", "SYNC\tbamfos-pack\tv0.2.4\tv0.2.5");
        onDisk(".bamfos-updater/journal.json", "{\"target_version\":\"v0.2.5\",\"ops\":[]}");
        onDisk(".bamfos-updater/last-sync.json", "{}");
        Manifest m = manifest(entry("config/bcc-common.toml", "v0.2.5", FileEntry.Side.BOTH, false));

        FactoryReset.arm(dir, m, Reconciler.Side.CLIENT, fetcher(), "v0.2.5", QUIET);

        List<String> deleted = Journal.load(Journal.pathIn(dir)).ops.stream()
                .filter(o -> o.kind().equals("delete")).map(Journal.Op::to).toList();
        assertFalse(deleted.contains(".bamfos-updater/bus.log"));
        assertTrue(deleted.contains(".bamfos-updater/last-sync.json"));
    }

    /** Arming a reset must carry over same-version journal ops outside the wipe set (e.g. servers.dat),
     * dropping ops from older target versions and letting reset ops win collisions. */
    @Test
    void armCarriesAStagedOpTheResetDoesNotItselfName() throws Exception {
        onDisk(".bamfos-updater/staging/seed-blob.part", "their servers.dat entry");
        Journal staged = new Journal();
        staged.targetVersion = "v0.2.5";
        staged.ops.add(new Journal.Op("seed", ".bamfos-updater/staging/seed-blob.part", "servers.dat", "sha"));
        staged.save(Journal.pathIn(dir));
        Manifest m = manifest(entry("config/bcc-common.toml", "v0.2.5", FileEntry.Side.BOTH, false));

        FactoryReset.arm(dir, m, Reconciler.Side.CLIENT, fetcher(), "v0.2.5", QUIET);

        Journal j = Journal.load(Journal.pathIn(dir));
        assertTrue(j.ops.stream().anyMatch(o -> o.kind().equals("seed") && o.to().equals("servers.dat")), String.valueOf(j.ops));
        assertFalse(j.ops.stream().anyMatch(o -> o.kind().equals("delete")
                        && o.to().equals(".bamfos-updater/staging/seed-blob.part")));
    }

    /** The version rule: a journal staged for ANOTHER release does not survive a reset (* rollback safety, after a version move the new plan is authoritative). */
    @Test
    void armDropsAStagedJournalForADifferentVersion() throws Exception {
        onDisk(".bamfos-updater/staging/seed-blob.part", "bytes for a version this reset is leaving");
        Journal staged = new Journal();
        staged.targetVersion = "v9.9.9";
        staged.ops.add(new Journal.Op("seed", ".bamfos-updater/staging/seed-blob.part", "servers.dat", "sha"));
        staged.save(Journal.pathIn(dir));
        Manifest m = manifest(entry("config/bcc-common.toml", "v0.2.5", FileEntry.Side.BOTH, false));

        FactoryReset.arm(dir, m, Reconciler.Side.CLIENT, fetcher(), "v0.2.5", QUIET);

        assertFalse(Journal.load(Journal.pathIn(dir)).ops.stream().anyMatch(o -> o.kind().equals("seed")));
    }

    /** Structural guard: journal.json is not walked today, so assert KEEPERS membership directly to
     * catch any future wipe-list expansion at arm time. */
    @Test
    void theJournalIsAKeeperEvenThoughNothingCurrentlyTriesToDeleteIt() {
        assertTrue(WipeSet.KEEPERS.contains(".bamfos-updater/journal.json"));
        assertTrue(WipeSet.KEEPERS.contains(".bamfos-updater/bus.log"));
    }

    /** Resume space checks must subtract already-staged bytes (payload - haveBytes) so a partial
     * download is not rejected for needing space it already has on disk. */
    @Test
    void theSpaceCheckAsksOnlyForTheBytesStillMissing() {
        long fourHundredMb = 400L * 1024 * 1024;
        long staged = 380L * 1024 * 1024;
        long free = 300L * 1024 * 1024;

        assertTrue(DiskSpace.room(fourHundredMb, free).blocks());
        assertFalse(DiskSpace.room(fourHundredMb - staged, free).blocks());
    }

    /** Assert the headroom margin directly on room(), not just on need(), so removing the margin
     * from the check fails the test. */
    @Test
    void theHeadroomIsTenPercentOrTwoFiftySixMegWhicheverIsBigger() {
        long plenty = Long.MAX_VALUE / 4;
        assertEquals(DiskSpace.FLOOR_BYTES, DiskSpace.need(0L));
        assertEquals(DiskSpace.FLOOR_BYTES, DiskSpace.room(0L, plenty).needBytes());
        long tenGig = 10L * 1024 * 1024 * 1024;
        assertEquals(tenGig + tenGig / 10, DiskSpace.need(tenGig));
        assertEquals(tenGig + tenGig / 10, DiskSpace.room(tenGig, plenty).needBytes());
    }

    /** Verify Armed reports reused staged files for ops logging and tests. */
    @Test
    void armedSaysHowManyFilesTheRetryDidNotHaveToFetch() throws Exception {
        Manifest m = manifest(
                entry("config/a.toml", "aaa", FileEntry.Side.BOTH, false),
                entry("mods/b.jar", "bbb", FileEntry.Side.BOTH, false));

        FactoryReset.Armed fresh = FactoryReset.arm(dir, m, Reconciler.Side.CLIENT, fetcher(), "v0.2.5", QUIET);
        assertEquals(0, fresh.reused());

        // arm again over the same staging: both parts are there and both verify
        FactoryReset.Armed again = FactoryReset.arm(dir, m, Reconciler.Side.CLIENT,
                (e, dest) -> fail("nothing should be fetched, both parts are already staged and valid"),
                "v0.2.5", QUIET);
        assertEquals(2, again.reused());
        assertEquals(2, again.places());

        // Confirm arm() checks only remaining bytes on resume rather than the full pack size.
        assertTrue(again.room().needBytes() < fresh.room().needBytes(), String.valueOf(fresh.room().needBytes()) + " / " + String.valueOf(again.room().needBytes()));
    }

    /** Resuming an interrupted reset reuses valid staged files instead of re-downloading everything and
     * failing the free-space check because of leftover staged files. */
    @Test
    void aRetryKeepsTheFilesTheFirstAttemptAlreadyDownloaded() throws Exception {
        Manifest m = manifest(
                entry("config/a.toml", "aaa", FileEntry.Side.BOTH, false),
                entry("mods/b.jar", "bbb", FileEntry.Side.BOTH, false),
                entry("mods/c.jar", "ccc", FileEntry.Side.BOTH, false));

        assertThrows(IOException.class,
                () -> FactoryReset.arm(dir, m, Reconciler.Side.CLIENT, failsAfter(2), "v0.2.5", QUIET));
        long staged;
        try (var s = Files.list(dir.resolve(".bamfos-updater/staging"))) {
            staged = s.count();
        }
        assertEquals(2, staged);

        // Retry with a fetcher that always throws, so this only passes if staged files are reused.
        int[] fetches = {0};
        Reconciler.Fetcher counting = (e, dest) -> {
            fetches[0]++;
            Files.createDirectories(dest.getParent());
            Files.write(dest, remote.get(e.path()));
        };
        FactoryReset.Armed armed = FactoryReset.arm(dir, m, Reconciler.Side.CLIENT, counting, "v0.2.5", QUIET);

        assertEquals(1, fetches[0]);
        assertEquals(3, armed.places());
        assertTrue(Journal.load(Journal.pathIn(dir)).replay(dir).allLanded());
        assertEquals("aaa", Files.readString(dir.resolve("config/a.toml")));
        assertEquals("ccc", Files.readString(dir.resolve("mods/c.jar")));
    }

    /** A truncated staged file fails hash verification and gets re-fetched instead of reused. */
    @Test
    void aHalfWrittenPartIsNeverReused() throws Exception {
        Manifest m = manifest(entry("mods/b.jar", "the whole thing", FileEntry.Side.BOTH, false));
        assertThrows(IOException.class,
                () -> FactoryReset.arm(dir, m, Reconciler.Side.CLIENT, failsAfter(0), "v0.2.5", QUIET));

        Path staging = dir.resolve(".bamfos-updater/staging");
        Files.createDirectories(staging);
        Path part = staging.resolve(HashUtils.sha256("the whole thing".getBytes(StandardCharsets.UTF_8))
                + "-fr0.part");
        Files.writeString(part, "the whol"); // the write that died

        int[] fetches = {0};
        FactoryReset.arm(dir, m, Reconciler.Side.CLIENT, (e, dest) -> {
            fetches[0]++;
            Files.createDirectories(dest.getParent());
            Files.write(dest, remote.get(e.path()));
        }, "v0.2.5", QUIET);

        assertEquals(1, fetches[0]);
        assertTrue(Journal.load(Journal.pathIn(dir)).replay(dir).allLanded());
        assertEquals("the whole thing", Files.readString(dir.resolve("mods/b.jar")));
    }

    /** Sweep staged leftovers from older releases before checking free space so stale files cannot
     * block the new attempt. */
    @Test
    void partsFromAnOlderReleaseAreSweptAndOurOwnAreNot() throws Exception {
        Path staging = dir.resolve(".bamfos-updater/staging");
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("deadbeef-fr0.part"), "a part for a release we are not arming");
        Files.writeString(staging.resolve("someothersync.part"), "a PENDING SYNC's part, not ours to touch");

        Manifest m = manifest(entry("mods/b.jar", "bbb", FileEntry.Side.BOTH, false));
        FactoryReset.arm(dir, m, Reconciler.Side.CLIENT, fetcher(), "v0.2.5", QUIET);

        assertFalse(Files.exists(staging.resolve("deadbeef-fr0.part")));
        assertTrue(Files.exists(staging.resolve("someothersync.part")));
    }

    /** Files replaced by a journal move shouldn't also be in the delete list, so there's no window where
     * the file is missing before the move runs. */
    @Test
    void aPathItPlacesIsNeverAlsoDeleted() throws Exception {
        Manifest m = manifest(entry("config/bcc-common.toml", "v0.2.5", FileEntry.Side.BOTH, false));

        FactoryReset.arm(dir, m, Reconciler.Side.CLIENT, fetcher(), "v0.2.5", QUIET);

        Journal j = Journal.load(Journal.pathIn(dir));
        List<String> deleted = j.ops.stream().filter(o -> o.kind().equals("delete")).map(Journal.Op::to).toList();
        assertFalse(deleted.contains("config/bcc-common.toml"), String.valueOf(deleted));
        assertTrue(deleted.contains("config/theirs.toml"));
        assertTrue(deleted.contains("mods/old.jar"));
        assertTrue(deleted.contains("options.txt"));
    }

    /** Aborting before journal.save must leave the existing install untouched. */
    @Test
    void aFetchThatDiesLeavesTheInstallCompletelyUntouched() throws IOException {
        Manifest m = manifest(
                entry("config/bcc-common.toml", "v0.2.5", FileEntry.Side.BOTH, false),
                entry("mods/create.jar", "create", FileEntry.Side.BOTH, false),
                entry("mods/jei.jar", "jei", FileEntry.Side.BOTH, false));

        assertThrows(IOException.class,
                () -> FactoryReset.arm(dir, m, Reconciler.Side.CLIENT, failsAfter(2), "v0.2.5", QUIET));

        assertFalse(Files.exists(Journal.pathIn(dir)));
        assertEquals("old", Files.readString(dir.resolve("config/bcc-common.toml")));
        assertEquals("hand edited", Files.readString(dir.resolve("config/theirs.toml")));
        assertTrue(Files.exists(dir.resolve("mods/old.jar")));
        assertEquals("fov:90", Files.readString(dir.resolve("options.txt")));
        assertTrue(Files.exists(dir.resolve("saves/World/level.dat")));
    }

    @Test
    void itNeverJournalsADeleteForAFileTheUpdaterNeeds() throws Exception {
        Manifest m = manifest(entry("config/bcc-common.toml", "v0.2.5", FileEntry.Side.BOTH, false));

        FactoryReset.arm(dir, m, Reconciler.Side.CLIENT, fetcher(), "v0.2.5", QUIET);

        Journal j = Journal.load(Journal.pathIn(dir));
        for (Journal.Op op : j.ops) {
            assertFalse(op.kind().equals("delete") && WipeSet.KEEPERS.contains(op.to()), String.valueOf(op.to()));
        }
    }

    /** Test the keeper guard directly rather than relying on the current directory walk never
     *  producing one. */
    @Test
    void theKeeperGuardRefusesAJournalThatWouldBrickTheBox() {
        Journal bad = new Journal();
        bad.ops.add(new Journal.Op("delete", "", "config/a.toml", ""));
        bad.ops.add(new Journal.Op("delete", "", "mods/bamfos-updater-boot.jar", ""));

        FactoryReset.Refused refused = assertThrows(FactoryReset.Refused.class,
                () -> FactoryReset.assertNoKeeperDeleted(bad));
        assertTrue(refused.why.contains("bamfos-updater-boot.jar"), refused.why);
        assertTrue(refused.why.contains("Nothing was changed"));
    }

    /** Factory reset restores shipped defaults whether files were modified or deleted. */
    @Test
    void optionalsComeBackAtTheirShippedDefaultNotTheStateTheFriendLeftThem() {
        FileEntry on = entry("mods/emf.jar", "on by default", FileEntry.Side.CLIENT, false);
        FileEntry off = entry("mods/journeymap.jar", "off by default", FileEntry.Side.CLIENT, true);

        assertEquals("mods/emf.jar", FactoryReset.target(on));
        assertEquals("mods/journeymap.jar.disabled", FactoryReset.target(off));
    }

    @Test
    void serverSideEntriesNeverReachAClientAndTheKeepersAreNeverReplaced() {
        Manifest m = manifest(
                entry("config/both.toml", "b", FileEntry.Side.BOTH, false),
                entry("config/clientonly.toml", "c", FileEntry.Side.CLIENT, false),
                entry("server.properties", "s", FileEntry.Side.SERVER, false),
                entry("mods/bamfos-updater-boot.jar", "the shim", FileEntry.Side.CLIENT, false));

        List<String> client = new ArrayList<>();
        FactoryReset.toPlace(m, Reconciler.Side.CLIENT).forEach(e -> client.add(e.path()));

        assertTrue(client.contains("config/both.toml"));
        assertTrue(client.contains("config/clientonly.toml"));
        assertFalse(client.contains("server.properties"));
        assertFalse(client.contains("mods/bamfos-updater-boot.jar"));
    }

    /** An empty file list would arm a journal that deletes everything and restores nothing. */
    @Test
    void aManifestWithNothingForThisSideIsRefusedRatherThanArmed() {
        Manifest m = manifest(entry("server.properties", "s", FileEntry.Side.SERVER, false));

        FactoryReset.Refused refused = assertThrows(FactoryReset.Refused.class,
                () -> FactoryReset.arm(dir, m, Reconciler.Side.CLIENT, fetcher(), "v0.2.5", QUIET));

        assertTrue(refused.why.contains("nothing to reset to"), refused.why);
        assertFalse(Files.exists(Journal.pathIn(dir)));
    }
}
