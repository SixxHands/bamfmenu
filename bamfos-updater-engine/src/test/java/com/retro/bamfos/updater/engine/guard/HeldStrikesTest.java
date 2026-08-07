package com.retro.bamfos.updater.engine.guard;

import com.retro.bamfos.updater.engine.Fx;
import com.retro.bamfos.updater.engine.journal.Journal;
import com.retro.bamfos.updater.engine.manifest.FileEntry;
import com.retro.bamfos.updater.engine.manifest.Manifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the three-part strike key and expiry logic using an injected clock. */
class HeldStrikesTest {

    @TempDir
    Path dir;

    private static final byte[] BYTES = "the release's bytes".getBytes(StandardCharsets.UTF_8);

    @AfterEach
    void restoreTheProbe() {
        HeldStrikes.PROBE_MILLIS = HeldStrikes.PROBE_DEFAULT; // Static field, so reset it after any test that changes it.
    }

    private void spendEveryChance(String path, String sha, String kind) {
        for (int boot = 0; boot < Journal.MAX_ATTEMPTS; boot++) {
            HeldStrikes.bump(dir, path, sha, kind, 1000L);
        }
    }

    /** A new release starts with a clean retry count instead of inheriting strikes from the old bytes. */
    @Test
    void newBytesReArmAGivenUpRow() {
        spendEveryChance("mods/cm.jar", "shaOLD", "REPLACE_BAK");
        assertTrue(HeldStrikes.exhausted(dir, "mods/cm.jar", "shaOLD", "REPLACE_BAK", 1000L));

        assertFalse(HeldStrikes.exhausted(dir, "mods/cm.jar", "shaNEW", "REPLACE_BAK", 1000L));
        assertFalse(HeldStrikes.bump(dir, "mods/cm.jar", "shaNEW", "REPLACE_BAK", 1000L));
        assertFalse(HeldStrikes.exhausted(dir, "mods/cm.jar", "shaNEW", "REPLACE_BAK", 1000L));
    }

    /** THE deadlock. A wedged .bak is a fact about the .bak name, and this rule' never takes one, so a strike
     *  earned under REPLACE_BAK must not stop a RESTORE of the very same bytes at the very same path. */
    @Test
    void strikesNeverTransferAcrossActionKinds() {
        spendEveryChance("mods/cm.jar", "sha", "REPLACE_BAK");

        assertFalse(HeldStrikes.exhausted(dir, "mods/cm.jar", "sha", "RESTORE", 1000L));
        assertTrue(HeldStrikes.exhausted(dir, "mods/cm.jar", "sha", "REPLACE_BAK", 1000L));
    }

    /** Spent rows get one re-probe per interval in case a transient blocker cleared, and a failed probe
     * buys no extra retries. */
    @Test
    void aSpentRowEarnsOneTryPerInterval() {
        HeldStrikes.PROBE_MILLIS = 100;
        spendEveryChance("mods/cm.jar", "sha", "REPLACE_BAK");

        assertTrue(HeldStrikes.exhausted(dir, "mods/cm.jar", "sha", "REPLACE_BAK", 1050L));
        assertFalse(HeldStrikes.exhausted(dir, "mods/cm.jar", "sha", "REPLACE_BAK", 1100L));

        assertFalse(HeldStrikes.bump(dir, "mods/cm.jar", "sha", "REPLACE_BAK", 1100L));
        assertTrue(HeldStrikes.exhausted(dir, "mods/cm.jar", "sha", "REPLACE_BAK", 1150L));
    }

    /** Only clear strikes on an actual commit. Non-repair plans skip this entry and staged runs
     *  don't prove the file can be replaced, so not hitting a refusal this run doesn't mean it recovered. */
    @Test
    void onlyACommittedRowIsForgotten() {
        HeldStrikes.bump(dir, "mods/cm.jar", "sha", "REPLACE_BAK", 1000L);
        HeldStrikes.bump(dir, "config/a.toml", "sha2", "REPLACE", 1000L);

        HeldStrikes.clear(dir, List.of("config/a.toml"));

        assertEquals("mods/cm.jar", HeldStrikes.names(dir));
        assertTrue(HeldStrikes.anyBelowMaxOrProbeDue(dir, 1000L));
    }

    /** Prune strike rows no longer in the manifest during sync so obsolete entries cannot permanently
     * veto the client fast path. */
    @Test
    void aRowTheReleaseMovedOnFrom_isPrunedOnRead() {
        FileEntry named = Fx.entry("mods/cm.jar", BYTES, FileEntry.Side.BOTH);
        Manifest manifest = Fx.manifest("vNEW", List.of(), named);
        HeldStrikes.bump(dir, "mods/cm.jar", named.sha256(), "REPLACE_BAK", 1000L);
        assertEquals(1, HeldStrikes.currentCount(dir, manifest));

        HeldStrikes.bump(dir, "mods/cm.jar", "the-sha-the-last-release-named", "REPLACE_BAK", 1000L);

        assertEquals(0, HeldStrikes.currentCount(dir, manifest));
        assertFalse(Files.exists(dir.resolve(".bamfos-updater").resolve("held-strikes")));
    }
}
