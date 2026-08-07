package com.retro.bamfmenu.early.core.repair;

import com.retro.bamfmenu.early.core.repair.api.PageRow;
import com.retro.bamfmenu.early.core.repair.api.ReleaseList;
import com.retro.bamfmenu.early.core.repair.api.RepairEffect;
import com.retro.bamfmenu.early.core.repair.api.RepairResult;
import com.retro.bamfmenu.early.core.repair.api.Tone;
import com.retro.bamfmenu.early.core.repair.api.UpdaterState;
import com.retro.bamfmenu.early.core.repair.items.lock.ClearLockItem;
import com.retro.bamfmenu.early.core.repair.items.optional.OptionalItem;
import com.retro.bamfmenu.early.core.repair.items.optional.page.OptionalPage;
import com.retro.bamfmenu.early.core.repair.items.rollback.page.RollbackPage;
import com.retro.bamfmenu.early.core.repair.items.status.StatusItem;
import com.retro.bamfmenu.early.core.repair.items.status.page.StatusPage;
import com.retro.bamfmenu.early.core.repair.session.RepairSample;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the repair subpages: displayed rows, selection behavior, and actions. */
class RepairPagesTest {

    private static final int WIDTH = 90;

    @Test
    void offlineTheRollbackPickerShowsCurrentOnlyAndNothingIsPickable() {
        UpdaterState offline = RepairSample.state("rollback-offline");
        List<PageRow> rows = new RollbackPage().rows(offline, WIDTH);
        assertEquals(1, count(rows, "v0.2.6"));
        assertTrue(text(rows).contains("INSTALLED"));
        assertTrue(text(rows).contains("Offline. Only the current installed version can be shown."));
        assertEquals(0, picks(rows));
        for (int i = 0; i < rows.size(); i++) {
            assertNull(new RollbackPage().activate(i, offline, WIDTH));
        }

        // and the rule is the CODE, not the row count: a non-live answer that somehow carried older rows
        // (a future engine change, a cached list) still offers nothing, because syncTo needs the network the
        // code just said we do not have
        UpdaterState wrong = RepairSample.state("idle").withReleases(new ReleaseList(ReleaseList.OFFLINE,
                List.of(new ReleaseList.Release("v0.2.6", "", "", false, true),
                        new ReleaseList.Release("v0.2.5", "", "2026-07-11T09:12:00Z", false, false))));
        List<PageRow> wrongRows = new RollbackPage().rows(wrong, WIDTH);
        assertEquals(0, picks(wrongRows));
        for (int i = 0; i < wrongRows.size(); i++) {
            assertNull(new RollbackPage().activate(i, wrong, WIDTH), String.valueOf(i));
        }
    }

    @Test
    void aDeadTokenAndAThrottleKeepTheirOwnSentences() {
        UpdaterState base = RepairSample.state("idle");
        String dead = text(new RollbackPage().rows(base.withReleases(
                new ReleaseList(ReleaseList.TOKEN_DEAD, List.of())), WIDTH));
        String limited = text(new RollbackPage().rows(base.withReleases(
                new ReleaseList(ReleaseList.RATE_LIMITED, List.of())), WIDTH));
        assertTrue(dead.contains("UPDATE TOKEN EXPIRED."), dead);
        assertTrue(limited.contains("GitHub is rate limiting. Try again later."), limited);
        assertFalse(limited.contains("Offline"));
    }

    @Test
    void theFirstVersionGagOnlyShowsWhenTheLiveListReallyHasNothingOlder() {
        assertTrue(text(rowsFor("rollback-first")).contains(RollbackPage.FIRST_VERSION_GAG));
        assertFalse(text(rowsFor("rollback")).contains(RollbackPage.FIRST_VERSION_GAG));
        assertFalse(text(rowsFor("rollback-offline")).contains(RollbackPage.FIRST_VERSION_GAG));
    }

    /**
     * Empty-list cases (no installed version, or empty live release array) must omit
     * the reason header and footer joke rather than drawing them above nothing.
     */
    @Test
    void aPickerWithNoRowsSaysWhatItHasInsteadOfWhyARowIsMissing() {
        UpdaterState base = RepairSample.state("idle");
        String offline = text(new RollbackPage().rows(
                base.withReleases(new ReleaseList(ReleaseList.OFFLINE, List.of())), WIDTH));
        assertTrue(offline.contains("Nothing installed yet, so there is nothing to roll back to."), offline);
        assertFalse(offline.contains("Only the installed version can be shown."), String.valueOf(offline));

        String empty = text(new RollbackPage().rows(
                base.withReleases(new ReleaseList(ReleaseList.LIVE, List.of())), WIDTH));
        assertTrue(empty.contains("This repo has no releases yet."), empty);
        assertFalse(empty.contains(RollbackPage.FIRST_VERSION_GAG), String.valueOf(empty));

        // One live row that does not match our installed version (deleted upstream) says nothing about older builds.
        String stranger = text(new RollbackPage().rows(base.withReleases(new ReleaseList(ReleaseList.LIVE,
                List.of(new ReleaseList.Release("v0.9.9", "", "", false, false)))), WIDTH));
        assertFalse(stranger.contains(RollbackPage.FIRST_VERSION_GAG), stranger);
    }

    /**
     * The engine updates independently, so a newer bamfmenu can talk to an older engine without listReleases.
     * That returns "error: unknown op listReleases" (parsed as an empty map with no code), which should not
     * be shown as "Offline".
     */
    @Test
    void anEngineThatPredatesTheOpIsNotOffline() {
        UpdaterState skew = RepairSample.state("idle").withReleases(ReleaseList.of(Map.of(), List.of()));
        assertEquals(ReleaseList.NO_ANSWER, skew.releases().code());
        String page = text(new RollbackPage().rows(skew, WIDTH));
        assertTrue(page.contains(RepairResult.noAnswer().message()), page);
        assertFalse(page.contains("Offline"), page);
        assertFalse(page.contains("Nothing installed yet"), String.valueOf(page));
    }

    /**
     * Dead-token errors must use alert styling instead of the neutral style used for transient network
     * errors so broken credentials stand out. Rate-limit messages stay neutral since they clear within an hour.
     */
    @Test
    void aDeadTokenIsDrawnLoudAndAThrottleIsNot() {
        UpdaterState base = RepairSample.state("idle");
        assertEquals(Tone.ALERT, toneOf(new RollbackPage().rows(
                        base.withReleases(new ReleaseList(ReleaseList.TOKEN_DEAD, List.of())), WIDTH),
                "UPDATE TOKEN EXPIRED."));
        assertEquals(Tone.DIM, toneOf(new RollbackPage().rows(
                        base.withReleases(new ReleaseList(ReleaseList.RATE_LIMITED, List.of())), WIDTH),
                "GitHub is rate limiting."));
    }

    @Test
    void pickingAReleaseNamesTheTagAndThePinItCosts() {
        UpdaterState live = RepairSample.state("rollback");
        RollbackPage page = new RollbackPage();
        List<PageRow> rows = page.rows(live, WIDTH);
        int first = -1;
        for (int i = 0; i < rows.size() && first < 0; i++) {
            if (rows.get(i).selectable()) {
                first = i;
            }
        }
        assertTrue(first >= 0);
        RepairEffect.Act act = page.activate(first, live, WIDTH);
        assertNotNull(act);
        assertEquals("Roll back to v0.2.5? This pins BAMFOS to the selected version.", act.confirms().get(0).body());
        RepairSessionTest.FakeOps ops = new RepairSessionTest.FakeOps();
        RepairResult result = act.action().run(ops);
        assertEquals("v0.2.5", ops.syncedTag);
        assertEquals(RepairResult.Kind.RESTART_REQUIRED, result.kind());
        assertEquals("Update staged. It applies the next time you start the game.", result.message());
    }

    @Test
    void thePinWarningIsOnEveryScreenThatCanCreateOrHoldAPin() {
        assertTrue(text(new RollbackPage().rows(RepairSample.state("rollback"), WIDTH))
                .contains(StatusPage.PIN_WARNING));
        assertTrue(text(new StatusPage().rows(RepairSample.state("status"), WIDTH))
                .contains(StatusPage.PIN_WARNING));
        assertFalse(text(new StatusPage().rows(RepairSample.state("idle"), WIDTH))
                .contains(StatusPage.PIN_WARNING));
    }

    @Test
    void theStatusPageReportsEveryFactAndAdmitsWhenItHasNone() {
        String pinned = text(new StatusPage().rows(RepairSample.state("status"), WIDTH));
        assertTrue(pinned.contains("Installed        : v0.2.6"), pinned);
        assertTrue(pinned.contains("Release Lock     : v0.2.4  PINNED"), pinned);
        assertTrue(pinned.contains("Last Sync        : ok, 14m ago"), pinned);
        assertTrue(pinned.contains("UPDATE TOKEN EXPIRED."));

        UpdaterState broken = new UpdaterState(true, "", "", false, "", 0L, 0L, false, 0, 0, true,
                "", List.of(), null, "", 0, "");
        String unreadable = text(new StatusPage().rows(broken, WIDTH));
        assertTrue(unreadable.contains("Status unavailable."), unreadable);
        assertTrue(unreadable.contains("Last Sync        : unknown"));
    }

    /**
     * Empty or error status maps arrive as "" and 0; rendering them as none/never/0 looks healthy
     * and falsely tells a pinned install that it tracks the server.
     */
    @Test
    void aStatusThatCameBackEmptyReadsUnknown_neverNoneOrZero() {
        UpdaterState blind = UpdaterState.of(Map.of(), List.of(), "", 1_780_000_000_000L);
        assertTrue(blind.statusUnreadable());

        String page = text(new StatusPage().rows(blind, WIDTH));
        assertTrue(page.contains("Status unavailable."), page);
        assertTrue(page.contains("Release Lock     : unknown"), page);
        assertTrue(page.contains("Disabled Mods    : unknown"), page);
        assertTrue(page.contains("Quarantined      : unknown"), page);
        assertFalse(page.contains(": none"), String.valueOf(page));
        assertFalse(page.contains(": 0"), String.valueOf(page));

        assertEquals("Could not read the release lock.", new ClearLockItem().item().whyNot(blind));
        assertEquals("unknown", new StatusItem().item().status().line(blind));
    }

    @Test
    void togglingAnOptionalModFlipsTheRowItIsLookingAt() {
        UpdaterState state = RepairSample.state("idle");
        OptionalPage page = new OptionalPage();
        List<PageRow> rows = page.rows(state, WIDTH);
        assertTrue(text(rows).contains("journeymap.jar"));
        assertTrue(text(rows).contains("pulled: kicked by the server"));

        RepairSessionTest.FakeOps ops = new RepairSessionTest.FakeOps();
        RepairEffect.Act on = page.activate(0, state, WIDTH); // journeymap is enabled, so ENTER turns it off
        assertNotNull(on);
        RepairResult offResult = on.action().run(ops);
        assertEquals("mods/optional/journeymap.jar", ops.toggledPath);
        assertTrue(ops.toggledTo);
        assertEquals("journeymap.jar is off next boot.", offResult.message());

        RepairEffect.Act back = page.activate(1, state, WIDTH); // shoulder-surfing is disabled, so ENTER turns it on
        assertNotNull(back);
        back.action().run(ops);
        assertEquals("mods/optional/shoulder-surfing.jar", ops.toggledPath);
        assertFalse(ops.toggledTo);
    }

    /** listOptional() returns optional and quarantined jars together; do not double-count quarantined ones. */
    @Test
    void theOptionalRowCountsOptionalsAndQuarantinedSeparately() {
        UpdaterState state = RepairSample.state("idle"); // two optionals and one quarantined jar, one list
        assertEquals("2 optional, 1 quarantined", new OptionalItem().item().status().line(state));
    }

    @Test
    void aRowTooWideToFitLosesItsNameAndKeepsItsState() {
        // Clip long (~100-char) jar names before the on/off column so the toggle state stays visible.
        List<PageRow> rows = new OptionalPage().rows(RepairSample.state("hostile"), 40);
        assertTrue(rows.get(0).text().endsWith("on"), rows.get(0).text());
        assertTrue(rows.get(1).text().endsWith("off"), rows.get(1).text());
        for (PageRow row : rows) {
            // Check mod rows only; unbreakable tokens in wrapped reason lines are clipped by the session (see layout test).
            if (row.selectable()) {
                assertTrue(row.text().length() <= 38, String.valueOf(row.text()));
            }
        }
    }

    /**
     * A jar missing both on-disk filenames must not display as "on",
     * and pressing ENTER must report the missing file instead of a generic log error.
     */
    @Test
    void anOptionalJarThatIsNotOnDiskSaysSoAndIsNotAChoice() {
        UpdaterState state = RepairSample.state("optional-missing");
        OptionalPage page = new OptionalPage();
        List<PageRow> rows = page.rows(state, WIDTH);
        assertTrue(rows.get(0).text().endsWith("missing"), rows.get(0).text());
        assertFalse(rows.get(0).selectable());
        assertNull(page.activate(0, state, WIDTH));
        assertTrue(text(rows).contains("Verify and Repair Files puts it back."), String.valueOf(text(rows)));
        assertTrue(rows.get(2).text().endsWith("off"), String.valueOf(rows.get(2).text()));
    }

    @Test
    void aReasonLineIsNotAChoice() {
        UpdaterState state = RepairSample.state("idle");
        OptionalPage page = new OptionalPage();
        List<PageRow> rows = page.rows(state, WIDTH);
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).selectable()) {
                assertNull(page.activate(i, state, WIDTH), String.valueOf(i));
            }
        }
    }

    @Test
    void theNotesPageScrollsInsteadOfSelecting() {
        List<PageRow> rows = new com.retro.bamfmenu.early.core.repair.items.notes.page.NotesPage()
                .rows(RepairSample.state("idle"), 40);
        assertEquals(0, picks(rows));
        for (PageRow row : rows) {
            assertTrue(row.text().length() <= 40, String.valueOf(row.text()));
        }
    }

    private static List<PageRow> rowsFor(String scenario) {
        return new RollbackPage().rows(RepairSample.state(scenario), WIDTH);
    }

    /* Returns the tone of the first row containing `needle` so tests can check styling, not just text. */
    private static Tone toneOf(List<PageRow> rows, String needle) {
        for (PageRow row : rows) {
            if (row.text().contains(needle)) {
                return row.tone();
            }
        }
        return null;
    }

    private static String text(List<PageRow> rows) {
        StringBuilder sb = new StringBuilder();
        for (PageRow row : rows) {
            sb.append(row.text()).append('\n');
        }
        return sb.toString();
    }

    private static int picks(List<PageRow> rows) {
        int n = 0;
        for (PageRow row : rows) {
            if (row.selectable()) {
                n++;
            }
        }
        return n;
    }

    private static int count(List<PageRow> rows, String needle) {
        int n = 0;
        for (PageRow row : rows) {
            if (row.text().contains(needle)) {
                n++;
            }
        }
        return n;
    }
}
