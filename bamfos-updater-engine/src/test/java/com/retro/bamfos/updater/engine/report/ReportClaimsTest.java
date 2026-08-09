package com.retro.bamfos.updater.engine.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that trouble report text matches actual behavior: accurate wording about how the report is
 * built, accurate NOT IN THIS FILE scope, and accurate FLOOR_ONLY recovery text.
 */
class ReportClaimsTest {

    @TempDir
    Path dir;

    private void aBoxOnTheFloor() throws IOException {
        ReportFixture.coreRejected(dir, "core-0004.jar", 1000L);
        ReportFixture.coreJar(dir, "core-0000.jar");
        ReportFixture.lastSync(dir, "ok");
    }

    private String only() throws IOException {
        List<Path> all = ReportFixture.reports(dir);
        assertEquals(1, all.size());
        return ReportFixture.read(all.get(0));
    }

    // Self-certification checks

    /** Header describes how the report is built instead of making an absolute redaction promise. */
    @Test
    void theHeaderSaysHowTheFileWasBuiltAndNeverCertifiesItClean() throws IOException {
        aBoxOnTheFloor();
        TroubleReport.consider(dir, "client");
        String body = only();

        assertFalse(body.contains("There is no password and no login token in this file"));
        assertFalse(body.contains("blanked out of this file"));
        assertTrue(body.contains("Every line here is a fixed message, a number, or a validated name"));
        assertTrue(body.contains("No log text, file paths, or tokens are included"));
    }

    /**
     * Plants sensitive strings in the five files reports previously read and verifies none leak into the output,
     * since the writer only accepts typed, Shape-gated values.
     */
    @Test
    void nothingAFriendCouldHaveTypedIntoThoseFilesReachesTheReport() throws IOException {
        String token = ReportFixture.fakeToken();
        String opaque = ReportFixture.fakeOpaque();
        ReportFixture.props(dir, "github_token=" + opaque + "\nrepo=" + token + "\ntag=" + token + "\n");
        ReportFixture.coreRejected(dir, "core-" + token + ".jar", 1000L);
        ReportFixture.coreJar(dir, "core-0000.jar");
        ReportFixture.ledgerWithQuarantine(dir, "mods/" + opaque + ".jar", opaque + " would not replace");
        ReportFixture.latestLog(dir, "[updater] the token is " + token + " right now",
                "[updater] Authorization: token " + opaque);
        Files.writeString(ReportFixture.own(dir).resolve("last-sync.json"),
                "{\"at\":1,\"tag\":\"" + token + "\",\"result\":\"" + opaque + "\",\"summary\":\"" + token
                        + "\",\"held\":false}", StandardCharsets.UTF_8);

        TroubleReport.consider(dir, "client");
        String body = only();

        assertFalse(ReportFixture.carriesAWindowOf(body, token));
        assertFalse(ReportFixture.carriesAWindowOf(body, opaque));
        // and the row is still there saying it could not name what was refused, because a silent drop reads as
        // "nothing was refused"
        assertTrue(body.contains("refused" + " ".repeat(10) + "unknown"), body);
    }

    /**
     * Full 64-char engine shas print as the 12-char prefix used by SyncCore.engineRefused so they stay matchable.
     */
    @Test
    void aRefusedEngineShaStillNamesTheBytesItBurned() throws IOException {
        String sha = "ab12cd34ef56" + "9".repeat(52); // full 64-char sha and the 12-char prefix printed in reports
        Files.createDirectories(ReportFixture.own(dir).resolve("engine"));
        ReportFixture.engineRejected(dir, sha, 1000L);
        ReportFixture.lastSync(dir, "ok");

        TroubleReport.consider(dir, "client");
        String body = only();

        assertTrue(body.contains("engine-refused"), body);
        assertTrue(body.contains("ab12cd34ef56"));
        assertFalse(body.contains(sha));
    }

    // Properties file checks

    /**
     * NOT IN THIS FILE must not claim bamfos-updater.properties is fully excluded since repo, lock, and pin
     * count are printed.
     */
    @Test
    void theSectionNamesTheTwoKeysItReallyDoesPrint() throws IOException {
        ReportFixture.props(dir, "repo=SixxHands/bamfos-pack\ntag=v0.2.4\npin=mods/a.jar,mods/b.jar\n"
                + "notes=C:\\users\\somebody\\a-path\nautorestart=false\n");
        aBoxOnTheFloor();

        TroubleReport.consider(dir, "client");
        String body = only();

        assertFalse(body.contains("anything at all out of .bamfos-updater/bamfos-updater.properties"));
        assertTrue(body.contains("only two values"));
        assertTrue(body.contains("which release this install is locked to and how many pins are set"));
        // and the claim keeps on the rows: the two named ones are there and nothing else out of that file is
        assertTrue(body.contains("release lock     v0.2.4"));
        assertTrue(body.contains("pins             2"));
        assertFalse(body.contains("mods/a.jar"));
        assertFalse(body.contains("a-path"));
        assertFalse(body.contains("autorestart"));
        // the repo is an ANSWER and not the value, because a credential pasted into that key would have ridden in
        // with no key name to give it away
        assertTrue(body.contains("repo             the pack owner's"), body);
        assertFalse(body.contains("SixxHands/bamfos-pack"));
    }

    // Floor-only fallback checks

    /**
     * FLOOR_ONLY must not claim future updates are blocked, since CoreCargo still accepts unrejected generations.
     */
    @Test
    void theFloorOnlyLineSaysWhatCoreCargoReallyDoes() throws IOException {
        aBoxOnTheFloor();
        TroubleReport.consider(dir, "client"); // core-refused
        TroubleReport.consider(dir, "client"); // floor-only

        String floor = null;
        for (Path p : ReportFixture.reports(dir)) {
            if (ReportFixture.read(p).contains("floor-only")) {
                floor = ReportFixture.read(p);
            }
        }
        assertTrue(floor != null);
        assertFalse(floor.contains("will not take a new one over the wire"));
        assertFalse(floor.contains("only the pack owner can get it moving again"));
        assertTrue(floor.contains("It will accept the next new release"));
        assertTrue(floor.contains("will not retry previously rejected ones"));
    }

    /** Checks the source behind the claim above so a future change making the refusal cover every
     *  generation won't make the report text inaccurate without failing a test. */
    @Test
    void coreCargoStillOnlyRefusesTheGenerationsItAlreadyBurned() throws IOException {
        Path cargo = ReportFixture.repoRoot().resolve("bamfos-updater-engine/src/main/java/com/retro/bamfos/"
                + "updater/engine/cargo/CoreCargo.java");
        String source = Files.readString(cargo, StandardCharsets.UTF_8);

        assertTrue(source.contains("rejected(dir).contains(target.getFileName().toString())"));
    }
}
