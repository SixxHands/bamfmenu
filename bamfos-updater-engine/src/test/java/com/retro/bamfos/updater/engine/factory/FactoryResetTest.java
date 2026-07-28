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
