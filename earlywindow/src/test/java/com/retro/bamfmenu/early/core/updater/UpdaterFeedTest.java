package com.retro.bamfmenu.early.core.updater;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the bus.log reader against synthetic files (no engine or GL needed) so parsed states
 * match BootBus.end (ok|skip|limited|offline|no-release|fail) and channel (ok|fail|idle|no-carrier|pinned).
 */
class UpdaterFeedTest {

    @TempDir
    Path dir;

    private UpdaterFeed feedOn(String... busLines) throws IOException {
        Path bam = dir.resolve(".bamfos-updater");
        Files.createDirectories(bam);
        // Create a dummy shim jar so the constructor doesn't log a "not present" line before sync events.
        Files.createDirectories(dir.resolve("mods"));
        Files.writeString(dir.resolve("mods").resolve("bamfos-updater-boot.jar"), "the shim");
        // UpdaterFeed truncates bus.log on construction, so write events afterward like a normal boot.
        UpdaterFeed feed = new UpdaterFeed(dir.toString());
        Files.writeString(bam.resolve("bus.log"), String.join("\n", busLines) + "\n", StandardCharsets.UTF_8);
        feed.poll();
        return feed;
    }

    /** Same fixture without a shim jar, simulating a boot with no updater installed. */
    private UpdaterFeed feedWithNoShim() throws IOException {
        Files.createDirectories(dir.resolve(".bamfos-updater"));
        return new UpdaterFeed(dir.toString());
    }

    /**
     * Cross-module wire format: BootBus writes literal "FPROG" and earlywindow matches it without
     * a shared constant, so this pins the exact tab-separated line by hand.
     */
    @Test
    void aProgressEventDrawsABarUnderTheFetchingRow() throws IOException {
        UpdaterFeed feed = feedOn(
                "SYNC\tbamfos-pack\tv0.2.4\tv0.2.6",
                "FILE\tcobblemon-1.6.1.jar",
                "FPROG\tcobblemon-1.6.1.jar\t25165824\t50331648"); // 24 of 48 MB
        List<UpdaterFeed.Line> rows = feed.postBlock(0);
        int fetching = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).text().startsWith("Fetching")) {
                fetching = i;
            }
        }
        assertTrue(fetching >= 0, String.valueOf(rows));
        assertTrue(fetching + 1 < rows.size());
        UpdaterFeed.Line bar = rows.get(fetching + 1);
        assertTrue(bar.text().contains("[###"), bar.text());
        assertTrue(bar.text().contains(" 50%"), bar.text());
        assertTrue(bar.text().endsWith("24.0/48.0 MB"), bar.text());
        assertTrue(bar.fill() > 0);
    }

    /** Reason is an optional third field on FILE; older two-field engines must still show the filename. */
    @Test
    void theActivityLineCarriesTheFileAndItsReason() throws IOException {
        UpdaterFeed feed = feedOn(
                "SYNC\tbamfos-pack\tv0.2.4\tv0.2.6",
                "FILE\tcreate-6.0.4.jar\tsha mismatch");
        assertTrue(feed.activity().contains("create-6.0.4.jar"), feed.activity());
        assertTrue(feed.activity().contains("sha mismatch"), feed.activity());
    }

    @Test
    void anOlderEngineSendsNoReasonAndStillNamesTheFile() throws IOException {
        UpdaterFeed feed = feedOn(
                "SYNC\tbamfos-pack\tv0.2.4\tv0.2.6",
                "FILE\tcreate-6.0.4.jar");
        assertEquals("create-6.0.4.jar", feed.activity());
    }

    /** Empty when idle so the panel falls back to the selected item help text. */
    @Test
    void activityIsBlankBeforeAnyFileAndAfterItFinishes() throws IOException {
        assertEquals("", feedOn("SYNC\tbamfos-pack\tv0.2.4\tv0.2.6").activity());
        assertEquals("", feedOn(
                "SYNC\tbamfos-pack\tv0.2.4\tv0.2.6",
                "FILE\tcreate-6.0.4.jar\tsha mismatch",
                "FDONE\tcreate-6.0.4.jar").activity());
    }

    /** FDONE clears the progress bar so the next file doesn't show stale progress before its first byte. */
    @Test
    void theBarDoesNotSurviveTheFileItBelongsTo() throws IOException {
        UpdaterFeed feed = feedOn(
                "SYNC\tbamfos-pack\tv0.2.4\tv0.2.6",
                "FILE\tcobblemon-1.6.1.jar",
                "FPROG\tcobblemon-1.6.1.jar\t25165824\t50331648",
                "FDONE\tcobblemon-1.6.1.jar",
                "FILE\tcreate-6.0.4.jar");
        for (UpdaterFeed.Line l : feed.postBlock(0)) {
            assertFalse(l.text().contains("[#"), String.valueOf(l.text()));
        }
    }

    /**
     * Step 0 factory reset moves already-fetched local files and never emits SYNC;
     * the apply block must still render progress instead of showing an empty terminal.
     */
    @Test
    void anApplyBlockDrawsWithNoSyncEventAtAll() throws IOException {
        UpdaterFeed feed = feedOn(
                "APPLY\tv0.2.5\t412\t879",
                "ASTEP\tremove\tconfig/gone.json\t412\t412",
                "ASTEP\tinstall\tmods/create-6.0.4.jar\t630\t879");
        String block = String.join("\n", feed.postBlock(0).stream().map(UpdaterFeed.Line::text).toList());

        assertTrue(block.contains("Applying BAMFOS"), block);
        assertTrue(block.contains("Target           : v0.2.5"), block);
        assertTrue(block.contains("Removing ............. 412 files  OK"), String.valueOf(block));
        assertTrue(block.contains("Installing ........... 630 of 879"), block);
        assertTrue(block.contains("[###"), String.valueOf(block));
        assertTrue(block.contains("630/879 files"), String.valueOf(block));
        assertTrue(block.contains("mods/create-6.0.4.jar"), block);
    }

    /** Verify pass gets a dedicated row and progress bar since hashing during a reset can take minutes. */
    @Test
    void theVerifyPhaseGetsItsOwnRow() throws IOException {
        UpdaterFeed feed = feedOn(
                "APPLY\tv0.2.5\t412\t879",
                "ASTEP\tverify\tmods/create-6.0.4.jar\t1291\t1291",
                "ADONE\tok");
        String block = String.join("\n", feed.postBlock(0).stream().map(UpdaterFeed.Line::text).toList());

        assertTrue(block.contains("Verifying ............ 1291 files  OK"), block);
        assertFalse(block.contains("Apply stopped"));
        assertFalse(feed.syncActive());
    }

    /** Pins column alignment between values and the progress bar bracket against label-length changes. */
    @Test
    void everyValueAndTheBarShareOneColumn() throws IOException {
        UpdaterFeed feed = feedOn(
                "APPLY\tv0.2.5\t412\t879",
                "ASTEP\tremove\tconfig/gone.json\t412\t412",
                "ASTEP\tinstall\tmods/create-6.0.4.jar\t630\t879");
        int column = -1;
        for (UpdaterFeed.Line l : feed.postBlock(0)) {
            String t = l.text();
            int at = t.indexOf(". ") >= 0 ? t.indexOf(". ") + 2 : t.indexOf('[');
            if (at < 0 || !t.contains("..")) {
                continue; // Title and active file path rows don't have a value column.
            }
            if (column < 0) {
                column = at;
            }
            assertEquals(column, at, String.valueOf(t));
        }
        assertEquals(23, column);
    }

    /** Blocks opening the BIOS while files are being moved, same as during a sync. */
    @Test
    void anApplyInFlightHoldsTheBiosShut() throws IOException {
        assertTrue(feedOn("APPLY\tv0.2.5\t412\t879").syncActive());
    }

    /** The block has to TERMINATE on something other than rows that all say OK, or a player reads a partial
     *  apply as a finished one. `partial` is files still stuck or bytes that did not read back. */
    @Test
    void aPartialApplyClosesOnTheHonestWord() throws IOException {
        UpdaterFeed feed = feedOn(
                "APPLY\tv0.2.5\t412\t879",
                "ASTEP\tinstall\tmods/create-6.0.4.jar\t878\t879",
                "ADONE\tpartial");
        List<UpdaterFeed.Line> rows = feed.postBlock(0);
        UpdaterFeed.Line last = rows.get(rows.size() - 1);

        assertEquals("Apply stopped ........ INCOMPLETE", last.text());
        assertTrue(last.bright());
        for (UpdaterFeed.Line l : rows) {
            assertFalse(l.text().contains("[#"), String.valueOf(l.text()));
        }
    }

    /**
     * Unknown phase names from newer engines should be ignored without altering counts,
     * since the shim can upgrade the engine independently of this jar.
     */
    @Test
    void aPhaseFromANewerEngineIsIgnoredNotGuessedAt() throws IOException {
        UpdaterFeed feed = feedOn(
                "APPLY\tv0.2.5\t2\t2",
                "ASTEP\tinstall\tmods/a.jar\t2\t2",
                "ASTEP\tdefragmenting\tmods/b.jar\t1\t9");
        String block = String.join("\n", feed.postBlock(0).stream().map(UpdaterFeed.Line::text).toList());

        assertTrue(block.contains("Installing ........... 2 files  OK"), String.valueOf(block));
        assertFalse(block.contains("1 of 9"), String.valueOf(block));
    }

    private static String[] midSync(String endState) {
        return new String[]{
                "SYNC\tbamfos-pack\tv0.2.4\tv0.2.6",
                "MANIFEST\t412",
                "FILE\tcobblemon-1.6.1.jar",
                "FDONE\tcobblemon-1.6.1.jar",
                "FILE\tcreate-6.0.4.jar", // Still downloading when rate limiting hits.
                "END\t" + endState,
        };
    }

    /**
     * Mid-download rate limit must close the Fetching row so the spinner does not keep running
     * under the skip line or leave scrolled "ok" rows looking like a finished update.
     */
    @Test
    void rateLimitedEnd_closesTheBlock_withNoFetchingRowLeftSpinning() throws IOException {
        UpdaterFeed feed = feedOn(midSync("limited"));
        List<UpdaterFeed.Line> block = feed.postBlock(0);

        assertFalse(block.isEmpty());
        assertTrue(block.stream().noneMatch(l -> l.text().startsWith("Fetching")), String.valueOf(block));
        assertEquals("Update paused ........ RATE LIMITED", block.get(block.size() - 1).text());
        assertTrue(block.get(block.size() - 1).bright());
        assertFalse(feed.syncActive());
    }

    /** A fail state gets a closing row and a reason line. Rate-limited skips the reason line since
     *  the engine already printed "github rate limited, sync skipped". */
    @Test
    void failedEnd_closesTheBlockAndEarnsTheWhyLine_limitedDoesNot() throws IOException {
        UpdaterFeed failed = feedOn(midSync("fail"));
        assertEquals("Update stopped ....... FAILED",
                failed.postBlock(0).get(failed.postBlock(0).size() - 1).text());
        assertEquals("updater: sync aborted, boot continues", drain(failed));

        UpdaterFeed limited = feedOn(midSync("limited"));
        assertNull(drain(limited));
    }

    /**
     * `no-release` should close the Fetching block without adding "sync aborted" under the engine
     * message. Matching `fail` literally (not "!ok") keeps older earlywindow builds safe.
     */
    @Test
    void aNoReleaseEnd_closesTheBlock_withoutStackingTheAbortLine() throws IOException {
        UpdaterFeed feed = feedOn(midSync("no-release"));
        List<UpdaterFeed.Line> block = feed.postBlock(0);

        assertEquals("Update stopped ....... FAILED", block.get(block.size() - 1).text());
        assertTrue(block.stream().noneMatch(l -> l.text().startsWith("Fetching")), String.valueOf(block));
        assertNull(drain(feed));
        assertFalse(feed.syncActive());
    }

    /** On a clean END, don't add another row since the two uppercase summary lines already closed the block. */
    @Test
    void okEnd_addsNoClosingRow() throws IOException {
        UpdaterFeed feed = feedOn("SYNC\tbamfos-pack\tv0.2.4\tv0.2.6",
                "MANIFEST\t412",
                "FDONE\tcobblemon-1.6.1.jar",
                "SYNCED\tv0.2.4\tv0.2.6",
                "END\tok");
        List<UpdaterFeed.Line> block = feed.postBlock(0);
        assertEquals("BAMFOS UPDATED FROM v0.2.4 TO v0.2.6.", block.get(block.size() - 1).text());
        assertNull(drain(feed));
    }

    /** When the bus ends before any SYNC, there is no block to close or extra row to add. */
    @Test
    void endBeforeAnySync_rendersNoBlockAtAll() throws IOException {
        UpdaterFeed feed = feedOn("LOG\tupdater: no carrier, sync skipped", "END\tskip");
        assertTrue(feed.postBlock(0).isEmpty());
        assertEquals("updater: no carrier, sync skipped", drain(feed));
    }

    /**
     * Keeps one Update Channel row whose status arrives after the check, not on IDENT,
     * since IDENT fires before network access and used to print OK on offline/idle boots.
     */
    @Test
    void theChannelRow_namesTheChannelAtIdent_andWaitsForTheVerdictToIndicate() throws IOException {
        UpdaterFeed quiet = feedOn("LOG\tcom.bamf.updater: kmod start");
        assertEquals(List.of("Update Channel   : Detecting ..."), quiet.hardwareRows());

        UpdaterFeed named = feedOn("IDENT\tSixxHands/bamfos-pack");
        assertEquals(List.of("Update Channel   : bamfos-pack"), named.hardwareRows());

        UpdaterFeed answered = feedOn("IDENT\tSixxHands/bamfos-pack", "CHANNEL\tok");
        assertEquals(List.of("Update Channel   : bamfos-pack  OK"), answered.hardwareRows());

        // Empty `repo=` shouldn't count as a channel name, otherwise we'd render "Update Channel   :   OK".
        UpdaterFeed blank = feedOn("IDENT\t", "CHANNEL\tok");
        assertEquals(List.of("Update Channel   : Detecting ..."), blank.hardwareRows());
    }

    /** Pins all five channel verdicts, with IDLE treated as the normal steady state when no check is needed. */
    @Test
    void everyVerdict_getsItsOwnWord() throws IOException {
        assertEquals("Update Channel   : bamfos-pack  IDLE", row("idle"));
        assertEquals("Update Channel   : bamfos-pack  OK", row("ok"));
        assertEquals("Update Channel   : bamfos-pack  FAILED", row("fail"));
        assertEquals("Update Channel   : bamfos-pack  NO CARRIER", row("no-carrier"));
        assertEquals("Update Channel   : bamfos-pack  PINNED", row("pinned"));
        assertEquals("Update Channel   : bamfos-pack", row("promoted"));
    }

    private String row(String state) throws IOException {
        return feedOn("IDENT\tSixxHands/bamfos-pack", "CHANNEL\t" + state).hardwareRows().get(0);
    }

    /** Without a shim jar, omit the channel row since the log already notes the updater isn't installed. */
    @Test
    void noShim_rendersNoHardwareRowsAndOneKmodLine() throws IOException {
        UpdaterFeed feed = feedWithNoShim();
        assertTrue(feed.hardwareRows().isEmpty());
        assertEquals("com.bamf.updater: kmod not found, package manager offline", drain(feed));
    }

    /** Formats the sync block channel row (`bamfos-pack`) the same way as the hardware row. */
    @Test
    void postBlockChannelRow_showsTheRepoNameNotTheAccount() throws IOException {
        UpdaterFeed feed = feedOn("SYNC\tSixxHands/bamfos-pack\tv0.2.4\tv0.2.6");
        List<UpdaterFeed.Line> block = feed.postBlock(0);
        assertTrue(block.stream().anyMatch(l -> l.text().equals("Update Channel   : bamfos-pack")),
                block.toString());
    }

    /**
     * A router that goes away mid-download is the WORLD refusing, not this box breaking, so it gets the same
     * treatment `limited` and `no-release` already had. On the old `fail` this rendered three rows about one
     * event: the door's own specific line, then a generic FAILED, then "updater: sync aborted, boot continues"
     * stacked under the line that had already explained it, vaguest last. The closing row wears the hardware
     * row's own words so the two read as one fact, same rule NO CARRIER follows everywhere else.
     */
    @Test
    void offlineEnd_saysNoCarrier_andStacksNoSecondWhyLine() throws IOException {
        UpdaterFeed feed = feedOn("SYNC\tbamfos-pack\tv0.2.4\tv0.2.6",
                "MANIFEST\t412",
                "FDONE\tcobblemon-1.6.1.jar",
                "FILE\tcreate-6.0.4.jar", // Still downloading when the connection drops.
                "LOG\tupdater: repository unreachable, boot continues",
                "CHANNEL\tno-carrier",
                "END\toffline");
        List<UpdaterFeed.Line> block = feed.postBlock(0);

        assertEquals("Update stopped ....... NO CARRIER", block.get(block.size() - 1).text(), String.valueOf(block));
        assertTrue(block.stream().noneMatch(l -> l.text().startsWith("Fetching")), String.valueOf(block));
        assertEquals("updater: repository unreachable, boot continues", drain(feed));
        assertFalse(feed.syncActive());
    }

    /**
     * Finale wording depends on whether the version changed, so a same-version repair
     * never prints "BAMFOS UPDATED FROM v0.2.6 TO v0.2.6".
     */
    @Test
    void aRepairFinale_neverClaimsAVersionMove() throws IOException {
        UpdaterFeed one = feedOn("SYNC\tbamfos-pack\tv0.2.6\tv0.2.6",
                "MANIFEST\t412", "PLAN\t1", "FDONE\tcm.jar", "SYNCED\tv0.2.6\tv0.2.6", "END\tok");
        assertEquals("BAMFOS REPAIRED 1 FILE ON v0.2.6.", lastOf(one));

        UpdaterFeed many = feedOn("SYNC\tbamfos-pack\tv0.2.6\tv0.2.6",
                "MANIFEST\t412", "PLAN\t3", "SYNCED\tv0.2.6\tv0.2.6", "END\tok");
        assertEquals("BAMFOS REPAIRED 3 FILES ON v0.2.6.", lastOf(many));

        UpdaterFeed moved = feedOn("SYNC\tbamfos-pack\tv0.2.4\tv0.2.6",
                "MANIFEST\t412", "PLAN\t9", "SYNCED\tv0.2.4\tv0.2.6", "END\tok");
        assertEquals("BAMFOS UPDATED FROM v0.2.4 TO v0.2.6.", lastOf(moved));
    }

    /**
     * Issue #189: finale uses the 4th SYNCED field (applied count) rather than plan count,
     * reporting shortfalls and omitting the banner when zero files were applied.
     */
    @Test
    void theFinaleWordsTheLandedCount_neverThePlan() throws IOException {
        UpdaterFeed none = feedOn("SYNC\tbamfos-pack\tv0.2.6\tv0.2.6",
                "MANIFEST\t412", "PLAN\t1", "SYNCED\tv0.2.6\tv0.2.6\t0", "END\tok");
        List<UpdaterFeed.Line> block = none.postBlock(0);
        assertTrue(block.stream().noneMatch(l -> l.text().startsWith("BAMFOS REPAIRED")), String.valueOf(block));
        assertTrue(block.stream().anyMatch(l -> l.text().equals("Update stopped ....... INCOMPLETE")), String.valueOf(block));
        assertTrue(block.stream().anyMatch(l -> l.text().equals("1 FILE HELD BACK.")), block.toString());
        assertTrue(block.stream().noneMatch(l -> l.text().startsWith("BIOS UPDATE COMPLETE")), String.valueOf(block));

        UpdaterFeed shortfall = feedOn("SYNC\tbamfos-pack\tv0.2.6\tv0.2.6",
                "MANIFEST\t412", "PLAN\t3", "SYNCED\tv0.2.6\tv0.2.6\t2", "END\tok");
        List<UpdaterFeed.Line> rows = shortfall.postBlock(0);
        assertTrue(rows.stream().anyMatch(l -> l.text().equals("BAMFOS REPAIRED 2 OF 3 FILES ON v0.2.6.")), rows.toString());
        assertEquals("1 FILE HELD BACK.", lastOf(shortfall));

        UpdaterFeed whole = feedOn("SYNC\tbamfos-pack\tv0.2.6\tv0.2.6",
                "MANIFEST\t412", "PLAN\t1", "SYNCED\tv0.2.6\tv0.2.6\t1", "END\tok");
        assertEquals("BAMFOS REPAIRED 1 FILE ON v0.2.6.", lastOf(whole));

        // a rollback whose whole plan is rule-E deletes downloads NOTHING and still converges, so zero
        // recorded there is the honest count of a move that really happened
        UpdaterFeed rollback = feedOn("SYNC\tbamfos-pack\tv0.2.6\tv0.2.4",
                "MANIFEST\t412", "PLAN\t0", "SYNCED\tv0.2.6\tv0.2.4\t0", "END\tok");
        assertEquals("BAMFOS UPDATED FROM v0.2.6 TO v0.2.4.", lastOf(rollback));
    }

    /**
     * Staged in-session syncs stop at the journal and send -1 so the reader keeps plan wording
     * instead of INCOMPLETE; older 3-field engines fall into the same compatibility branch.
     */
    @Test
    void anUnmeasuredCount_keepsTodaysWording() throws IOException {
        UpdaterFeed staged = feedOn("SYNC\tbamfos-pack\tv0.2.4\tv0.2.6",
                "MANIFEST\t412", "PLAN\t3", "SYNCED\tv0.2.4\tv0.2.6\t-1", "PENDING", "END\tok");
        List<UpdaterFeed.Line> block = staged.postBlock(0);
        assertEquals("BIOS UPDATE COMPLETE. REBOOT TO APPLY.", lastOf(staged));
        assertTrue(block.stream().anyMatch(l -> l.text().equals("BAMFOS UPDATED FROM v0.2.4 TO v0.2.6.")), block.toString());
        assertTrue(block.stream().noneMatch(l -> l.text().contains("INCOMPLETE")), String.valueOf(block));
        assertTrue(block.stream().noneMatch(l -> l.text().contains("HELD BACK")), block.toString());

        UpdaterFeed older = feedOn("SYNC\tbamfos-pack\tv0.2.6\tv0.2.6",
                "MANIFEST\t412", "PLAN\t3", "SYNCED\tv0.2.6\tv0.2.6", "END\tok");
        assertEquals("BAMFOS REPAIRED 3 FILES ON v0.2.6.", lastOf(older));
    }

    /** When only the updater jars changed, the engine skips SYNCED and just prints REBOOT TO APPLY.
     *  Don't synthesize an extra summary row here. */
    @Test
    void aSelfEntryOnlySync_drawsThePendingLineAndNoFinale() throws IOException {
        UpdaterFeed feed = feedOn("SYNC\tbamfos-pack\tv0.2.6\tv0.2.6",
                "MANIFEST\t412", "PLAN\t0", "PENDING", "END\tok");
        List<UpdaterFeed.Line> block = feed.postBlock(0);
        assertEquals("BIOS UPDATE COMPLETE. REBOOT TO APPLY.", lastOf(feed));
        assertTrue(block.stream().noneMatch(l -> l.text().startsWith("BAMFOS ")), String.valueOf(block));
    }

    private static String lastOf(UpdaterFeed feed) {
        List<UpdaterFeed.Line> block = feed.postBlock(0);
        return block.get(block.size() - 1).text();
    }

    /**
     * Hand-dropped release jars keep version suffixes in the filename (matching EmbeddedToken's prefix scan).
     * FML loads them by service file, so detection must use prefix matching instead of an exact filename.
     */
    @Test
    void aHandDroppedVersionedShim_countsAsInstalled() throws IOException {
        Files.createDirectories(dir.resolve(".bamfos-updater"));
        Files.createDirectories(dir.resolve("mods"));
        Files.writeString(dir.resolve("mods").resolve("bamfos-updater-boot-0.0.3.jar"), "the shim");
        UpdaterFeed feed = new UpdaterFeed(dir.toString());

        assertNull(drain(feed));
        assertEquals(List.of("Update Channel   : Detecting ..."), feed.hardwareRows());
    }

    /** FML only loads *.jar, so a .disabled shim shouldn't show up as installed. */
    @Test
    void aParkedShim_doesNotCountAsInstalled() throws IOException {
        Files.createDirectories(dir.resolve(".bamfos-updater"));
        Files.createDirectories(dir.resolve("mods"));
        Files.writeString(dir.resolve("mods").resolve("bamfos-updater-boot.jar.disabled"), "parked");
        UpdaterFeed feed = new UpdaterFeed(dir.toString());

        assertTrue(feed.hardwareRows().isEmpty());
        assertEquals("com.bamf.updater: kmod not found, package manager offline", drain(feed));
    }

    /** Drains the queue and returns the last line, or null if nothing was queued. */
    private static String drain(UpdaterFeed feed) {
        String last = null;
        for (String s = feed.nextLogLine(); s != null; s = feed.nextLogLine()) {
            last = s;
        }
        return last;
    }
}
