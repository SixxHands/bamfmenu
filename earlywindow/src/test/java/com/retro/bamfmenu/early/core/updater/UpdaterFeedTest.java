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
 * The READER half of the boot bus, which is the half nothing used to guard: the engine's ClientDoorTest pins
 * the writer's END states, so a rename there goes red, while this side could silently drift and the player
 * would just read two rows contradicting each other with nothing failing anywhere. The bus IS a file, so a
 * synthetic bus.log is the whole fixture, no engine and no GL context needed (BootBus.end writes
 * ok | skip | limited | no-release | fail, BootBus.channel writes ok | fail | idle | no-carrier | pinned).
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
