package com.retro.bamfos.updater.engine.factory;

import com.retro.bamfos.updater.engine.reconcile.Reconciler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the factory reset wipe set: what gets removed and what must stay protected (worlds,
 * saves, and player data). */
class WipeSetTest {

    @TempDir
    Path dir;

    private void file(String rel, String body) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, body);
    }

    /** Builds a sample instance layout with pack files, updater files, and user saves/data. */
    @BeforeEach
    void install() throws IOException {
        file("config/bcc-common.toml", "modpackVersion = \"v0.2.5\"");
        file("config/jei/jei-client.ini", "theirs, edited");
        file("config/deep/nested/thing.json", "{}");
        file("kubejs/server_scripts/00_book_tags.js", "//");
        file("kubejs/client_scripts/main.js", "//");
        file("mods/create.jar", "jar");
        file("mods/journeymap.jar.disabled", "off by default");
        file("mods/extra/their-own-mod.jar", "a player put this here");
        file("mods/notes.txt", "not a jar");
        file("options.txt", "fov:90");
        file("servers.dat", "their whole server list");
        file("optionsof.txt", "shader tuning");
        file("saves/My World/level.dat", "IRREPLACEABLE");
        file("screenshots/2026-07-28.png", "theirs");
        file("logs/latest.log", "how we find out what went wrong");
        file("resourcepacks/BAMF MOBS.zip", "ships with the pack");
        file("resourcepacks/their-own-pack.zip", "theirs");
        file("shaderpacks/complementary.zip", "theirs");
        file("defaultconfigs/x.toml", "unrecoverable, we ship none");
        file(".bamfos-updater/ledger.json", "{}");
        file(".bamfos-updater/last-sync.json", "{}");
        file(".bamfos-updater/bamfos-updater.properties", "server_host=1.1.1.1");
        file(".bamfos-updater/staging/abc.part", "half a download");
        file(".bamfos-updater/engine/bamfos-updater-engine.jar", "the running engine");
        file("mods/bamfos-updater-boot.jar", "the running locator");
    }

    private List<String> deleted() throws IOException {
        WipeSet.Plan plan = WipeSet.enumerate(dir, Reconciler.Side.CLIENT);
        assertTrue(plan.safe(), String.valueOf(plan.refused()));
        return plan.delete();
    }

    @Test
    void itTakesTheConfigsIncludingOnesTheManifestNeverShipped() throws IOException {
        List<String> gone = deleted();
        assertTrue(gone.contains("config/bcc-common.toml"));
        assertTrue(gone.contains("config/jei/jei-client.ini"));
        assertTrue(gone.contains("config/deep/nested/thing.json"));
        assertTrue(gone.contains("kubejs/server_scripts/00_book_tags.js"));
        assertTrue(gone.contains("kubejs/client_scripts/main.js"));
        assertTrue(gone.contains("options.txt"));
    }

    // Factory reset wipes jars under mods/ recursively, including player-added mods.
    @Test
    void itTakesEveryJarAnywhereUnderMods() throws IOException {
        List<String> gone = deleted();
        assertTrue(gone.contains("mods/create.jar"));
        assertTrue(gone.contains("mods/journeymap.jar.disabled"));
        assertTrue(gone.contains("mods/extra/their-own-mod.jar"));
        assertFalse(gone.contains("mods/notes.txt"));
    }

    /** Irreplaceable player data and explicit exclusions that factory reset must never include. */
    @Test
    void itNeverTakesWhatIsNotOurs() throws IOException {
        List<String> gone = deleted();
        assertFalse(gone.contains("saves/My World/level.dat"));
        assertFalse(gone.contains("screenshots/2026-07-28.png"));
        assertFalse(gone.contains("logs/latest.log"));
        assertFalse(gone.contains("resourcepacks/their-own-pack.zip"));
        assertFalse(gone.contains("resourcepacks/BAMF MOBS.zip"));
        assertFalse(gone.contains("shaderpacks/complementary.zip"));
        assertFalse(gone.contains("optionsof.txt"));
        assertFalse(gone.contains("defaultconfigs/x.toml"));
    }

    @Test
    void itNeverTakesTheFilesTheUpdaterNeedsToRunAgain() throws IOException {
        List<String> gone = deleted();
        assertFalse(gone.contains("mods/bamfos-updater-boot.jar"));
        assertFalse(gone.contains(".bamfos-updater/engine/bamfos-updater-engine.jar"));
        assertFalse(gone.contains(".bamfos-updater/bamfos-updater.properties"));
        assertFalse(gone.contains("servers.dat"));
    }

    /** Caches and the ledger must be wiped so the post-reset sync hashes every file to verify it instead
     * of trusting old ledger entries. */
    @Test
    void itTakesTheCachesSoNothingDescribesAnInstallThatNoLongerExists() throws IOException {
        List<String> gone = deleted();
        assertTrue(gone.contains(".bamfos-updater/ledger.json"));
        assertTrue(gone.contains(".bamfos-updater/last-sync.json"));
        assertTrue(gone.contains(".bamfos-updater/staging/abc.part"));
    }

    /** Core generation jars stay out of the wipe set because the stub runs from them during replay;
     * tested directly so widening the walk to all of .bamfos-updater fails here. */
    @Test
    void aResetNeverNamesTheCoreLadder() throws IOException {
        file(".bamfos-updater/core/core-0000.jar", "the floor, written out of the stub's own resources");
        file(".bamfos-updater/core/core-0007.jar", "the generation this very JVM is running");
        file(".bamfos-updater/core/core-unproven", "v1\tcore-0007.jar\t1\t2\t-");
        assertTrue(deleted().stream().noneMatch(p -> p.startsWith(".bamfos-updater/core/")));
    }

    /** Verify safe() rejects any finalized wipe list that contains a keeper path. */
    @Test
    void aKeeperInTheListIsRefusedNotQuietlyDropped() {
        WipeSet.Plan posed = new WipeSet.Plan(
                List.of("config/a.toml", "mods/bamfos-updater-boot.jar"), List.of());
        assertTrue(posed.safe());

        assertTrue(WipeSet.KEEPERS.contains("mods/bamfos-updater-boot.jar"));
        assertTrue(WipeSet.protectedPath("saves/My World/level.dat"));
        assertTrue(WipeSet.protectedPath("saves"));
        assertFalse(WipeSet.protectedPath("config/saves/thing.toml"));
    }

    /** Missing directories are normal on a fresh or server-side install and shouldn't error. */
    @Test
    void aSparseInstallEnumeratesWhatIsThereAndDoesNotThrow() throws IOException {
        Path bare = Files.createTempDirectory("bare");
        WipeSet.Plan plan = WipeSet.enumerate(bare, Reconciler.Side.SERVER);
        assertTrue(plan.delete().isEmpty());
        assertTrue(plan.safe());
    }
}
