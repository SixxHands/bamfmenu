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
 * synthetic bus.log is the whole fixture, no engine and no GL context needed (BootBus.end's four states are
 * ok | skip | limited | fail).
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

    /** The updater rows are POST rows: fixed footprint from the first frame, each one earning its value when
     *  the engine answers, exactly the contract the Display Adapter row above them already runs on. The door
     *  does not open until FML reaches the locator phase, so Detecting ... is the honest state until then and
     *  a default this side filled in would be the dishonest one. */
    @Test
    void hardwareRows_detectUntilTheEngineIdentifies_thenCarryRealValues() throws IOException {
        UpdaterFeed quiet = feedOn("LOG\tcom.bamf.updater: kmod start");
        assertEquals(List.of(
                "Update Channel   : Detecting ...",
                "Updater Module   : Detecting ...",
                "Release Lock     : Detecting ..."), quiet.hardwareRows());

        UpdaterFeed known = feedOn("IDENT\tSixxHands/bamfos-pack\t1.0.1\t");
        assertEquals(List.of(
                "Update Channel   : bamfos-pack  OK", // the repo NAME, never the pack's account with it
                "Updater Module   : 1.0.1  OK",
                "Release Lock     : none"), known.hardwareRows());

        // Empty `repo=` shouldn't count as a channel name, otherwise we'd render "Update Channel   :   OK".
        UpdaterFeed blank = feedOn("IDENT\t\t\t");
        assertEquals("Update Channel   : Detecting ...", blank.hardwareRows().get(0));
    }

    /** A pin is the one row here that changes what the box DOES, so it says so instead of reading like any
     *  other value. */
    @Test
    void pinnedIdent_marksTheReleaseLock() throws IOException {
        UpdaterFeed feed = feedOn("IDENT\tSixxHands/bamfos-pack\t1.0.1\tv0.2.4");
        assertEquals("Release Lock     : v0.2.4  PINNED", feed.hardwareRows().get(2));
    }

    /** No shim jar means there is no channel, no build and no lock, and the log line already said the kmod is
     *  missing, so three NOT INSTALLED rows would only say it three more times. */
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

    /** Drains the queue and returns the last line, or null if nothing was queued. */
    private static String drain(UpdaterFeed feed) {
        String last = null;
        for (String s = feed.nextLogLine(); s != null; s = feed.nextLogLine()) {
            last = s;
        }
        return last;
    }
}
