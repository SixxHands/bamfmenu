package com.retro.bamfmenu.early.core.repair;

import com.retro.bamfmenu.early.core.repair.api.ReleaseList;
import com.retro.bamfmenu.early.core.repair.api.RepairAction;
import com.retro.bamfmenu.early.core.repair.api.RepairContext;
import com.retro.bamfmenu.early.core.repair.api.RepairItem;
import com.retro.bamfmenu.early.core.repair.api.RepairOps;
import com.retro.bamfmenu.early.core.repair.api.RepairResult;
import com.retro.bamfmenu.early.core.repair.api.Tone;
import com.retro.bamfmenu.early.core.repair.api.UpdaterState;
import com.retro.bamfmenu.early.core.repair.input.RepairIntent;
import com.retro.bamfmenu.early.core.repair.registry.RepairRegistry;
import com.retro.bamfmenu.early.core.repair.session.RepairSample;
import com.retro.bamfmenu.early.core.repair.session.RepairSession;
import com.retro.bamfmenu.early.core.repair.view.RepairBox;
import com.retro.bamfmenu.early.core.repair.view.RepairFrame;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the UPDATE AND REPAIR menu, gates, refusals, and frames against posed snapshots
 * so offline, pinned, and mid-sync states run headless without a game or network.
 */
class RepairSessionTest {

    private static final int WIDTH = 132; // Column count for a 16:9 window with the default font.
    private static final int ROWS = 14;

    @Test
    void discoveryFindsEveryRowInSpecOrder() {
        List<RepairItem> items = new RepairRegistry().items();
        List<String> ids = new ArrayList<>();
        for (RepairItem item : items) {
            ids.add(item.id());
        }
        assertEquals(List.of("status", "verify", "sync", "optional", "notes", "rollback", "server",
                        "clearlock", "factory"), ids);
        assertEquals("factory", ids.get(ids.size() - 1));
    }

    @Test
    void aRowWithNothingBehindItSaysWhyAndStartsNothing() {
        UpdaterState empty = withNoOptionalMods();
        RepairSession s = session(empty);
        select(s, "optional");
        RepairFrame frame = s.frame(WIDTH, ROWS, 0);
        assertEquals(Tone.DIM, leftToneOf(frame, "Optional Mods"));

        Recorder host = new Recorder();
        s.key(RepairIntent.ACTIVATE, host);
        assertEquals("No optional mods in this release.", s.lastMessage());
        assertNull(s.openPage());
        assertFalse(s.busy());
        assertEquals(0, host.runs);
    }

    @Test
    void everyFileActionRefusesWhileTheEngineIsWriting() {
        UpdaterState syncing = RepairSample.state("idle").live(0L, true);
        RepairSession s = session(syncing);
        select(s, "verify");
        Recorder host = new Recorder();
        s.key(RepairIntent.ACTIVATE, host);
        assertEquals("Update in progress. Actions locked.", s.lastMessage());
        assertEquals(0, host.runs);
        assertNull(s.confirm());

        // The same check covers subpage rows too.
        RepairSession page = session(RepairSample.state("optional"));
        select(page, "optional");
        page.key(RepairIntent.ACTIVATE, host);
        assertNotNull(page.openPage());
        page.state(page.state().live(0L, true)); // the engine starts writing while the page is open
        page.key(RepairIntent.ACTIVATE, host);
        assertEquals("Update in progress. Actions locked.", page.lastMessage());
        assertEquals(0, host.runs);
    }

    @Test
    void aRunningActionLocksTheWayOutSoAHandOffCannotRaceAWrite() {
        RepairSession s = RepairSample.session("working");
        assertTrue(s.busy());
        Recorder host = new Recorder();
        s.key(RepairIntent.BACK, host);
        assertEquals(0, host.leaves);
        assertEquals("Update in progress. Actions locked.", s.lastMessage());
        int before = s.selection();
        s.key(RepairIntent.DOWN, host);
        assertEquals((before + 1) % s.items().size(), s.selection());
    }

    @Test
    void nothingRunsBeforeTheSnapshotLands() {
        RepairSession s = session(UpdaterState.reading());
        Recorder host = new Recorder();
        s.key(RepairIntent.ACTIVATE, host);
        assertEquals("Reading the updater ...", s.lastMessage());
        assertEquals(0, host.runs);
        assertNull(s.openPage());
        assertEquals(Tone.NORMAL, leftToneOf(s.frame(WIDTH, ROWS, 0), "Optional Mods"));
    }

    /** Guards the farewell prompt (the only exit path in this window) against firing unexpectedly. */
    @Test
    void onlyARestartRequiredResultEverArmsTheFarewell() {
        Recorder host = new Recorder();
        for (RepairResult.Kind kind : new RepairResult.Kind[]{RepairResult.Kind.DONE,
                RepairResult.Kind.STAGED, RepairResult.Kind.REFUSED, RepairResult.Kind.FAILED}) {
            RepairSession s = session(RepairSample.state("idle"));
            s.done(new RepairResult(kind, "whatever happened"));
            s.tick(host);
            assertEquals(0, host.closes, kind + " must never close the game");
            assertNull(s.confirm(), kind + " must not put a farewell box up");
        }
    }

    @Test
    void theFarewellCountsDownAndClosesWhenItRunsOut() {
        RepairSession s = session(RepairSample.state("idle").live(1_000L, false));
        Recorder host = new Recorder();
        s.done(new RepairResult(RepairResult.Kind.RESTART_REQUIRED, "reset armed"));

        assertEquals("Restart Required", s.confirm().title());
        assertTrue(s.confirm().body().contains("Your game will now close"), s.confirm().body());
        assertEquals("Ok", s.confirm().yes());
        assertEquals(10, s.closingIn());

        s.tick(host);
        assertEquals(0, host.closes);

        s.state(s.state().live(1_000L + 9_000L, false));
        assertEquals(1, s.closingIn());
        s.tick(host);
        assertEquals(0, host.closes);

        s.state(s.state().live(1_000L + 10_000L, false));
        s.tick(host);
        assertEquals(1, host.closes);
    }

    /** The box says "press any key", so any key has to take it, not one particular key. */
    @Test
    void anyKeyTakesTheFarewellImmediately() {
        for (RepairIntent intent : new RepairIntent[]{RepairIntent.ACTIVATE, RepairIntent.BACK,
                RepairIntent.DOWN, RepairIntent.LEFT}) {
            RepairSession s = session(RepairSample.state("idle").live(1_000L, false));
            Recorder host = new Recorder();
            s.done(new RepairResult(RepairResult.Kind.RESTART_REQUIRED, "reset armed"));
            s.key(intent, host);
            assertEquals(1, host.closes, intent + " should close the game since the prompt accepts any key");
            assertEquals(0, host.leaves, intent + " must not walk out to SETUP instead");
        }
    }

    @Test
    void escWalksOneDepthAtATimeAndOnlyLeavesFromTheList() {
        RepairSession s = RepairSample.session("optional");
        Recorder host = new Recorder();
        assertNotNull(s.openPage());
        s.key(RepairIntent.BACK, host);
        assertNull(s.openPage());
        assertEquals(0, host.leaves);
        s.key(RepairIntent.BACK, host);
        assertEquals(1, host.leaves);
    }

    @Test
    void aConfirmPromptOwnsTheKeysUntilItIsAnswered() {
        RepairSession s = RepairSample.session("confirm");
        assertEquals("Verify and repair every managed file?", s.confirm().body());
        Recorder host = new Recorder();
        s.key(RepairIntent.DOWN, host);
        assertEquals("Verify and repair every managed file?", s.confirm().body());
        s.key(RepairIntent.BACK, host);
        assertNull(s.confirm());
        assertEquals("Cancelled.", s.lastMessage());
        assertEquals(0, host.runs);

        RepairSession yes = RepairSample.session("confirm");
        yes.key(RepairIntent.ACTIVATE, host);
        assertEquals(1, host.runs);
        assertTrue(yes.busy());
    }

    /** The prompt is the one gap where a player's own reading time sits between the gate and the write, and
     *  the engine can start writing inside it. Nothing else stands in the way: InstanceLock reads our own
     *  pid as a dead holder, deletes the live lock file and hands out a second lock in the same JVM. */
    @Test
    void aPromptAnsweredAfterTheEngineStartedWritingIsStillRefused() {
        RepairSession s = RepairSample.session("confirm");
        assertEquals("Verify and repair every managed file?", s.confirm().body());
        s.state(s.state().live(0L, true)); // Sync starts while the confirmation dialog is open.
        Recorder host = new Recorder();
        s.key(RepairIntent.ACTIVATE, host);
        assertEquals(0, host.runs);
        assertEquals(RepairResult.locked().message(), s.lastMessage());
        assertFalse(s.busy());
    }

    /** The bar used to say all keys were locked even though UP/DOWN still moved the selection and F2 still
     *  opened the volume menu (handled before this screen sees it). */
    @Test
    void theBusyBarNamesOnlyTheKeysThatAreReallyLocked() {
        RepairSession s = RepairSample.session("working");
        assertTrue(s.busy());
        String bar = s.hints();
        assertTrue(bar.contains("ENTER and ESC are locked"), bar);
        assertTrue(bar.contains("F2  Volume"), bar);
        Recorder host = new Recorder();
        int before = s.selection();
        s.key(RepairIntent.DOWN, host);
        assertNotEquals(before, s.selection());
    }

    @Test
    void theWorkerAnswerIsWhatThePanelPrints() {
        RepairSession s = RepairSample.session("confirm");
        Recorder host = new Recorder();
        s.key(RepairIntent.ACTIVATE, host);
        FakeOps ops = new FakeOps();
        ops.repairCode = 5; // GitHub is throttling us
        s.done(host.action.run(ops));
        assertFalse(s.busy());
        assertEquals("GitHub is rate limiting. Try again later.", s.lastMessage());
        assertEquals(1, ops.repairs);
    }

    /* Includes a 100-char jar name and unbroken 300-char URL because wrap() only breaks on spaces,
       so clipping must keep the right border inside the frame. */
    @Test
    void noCellEverOverflowsItsColumnInAnyPosedState() {
        for (String scenario : RepairSample.SCENARIOS) {
            RepairSession s = RepairSample.session(scenario);
            RepairFrame frame = s.frame(WIDTH, ROWS, 7);
            int rightInner = RepairBox.rightInner(WIDTH);
            assertEquals(frame.topBar().length(), frame.bottomBar().length(), scenario + ": the bars must match");
            for (RepairFrame.Row row : frame.rows()) {
                assertTrue(row.left().length() <= RepairBox.LEFT_INNER,
                        scenario + ": left cell overflowed with '" + row.left() + "'");
                assertTrue(row.right().length() <= rightInner,
                        scenario + ": right cell overflowed with '" + row.right() + "'");
            }
        }
    }

    @Test
    void theBusyLineNamesTheActionAndSpins() {
        RepairSession s = RepairSample.session("working");
        String first = noticeOf(s.frame(WIDTH, ROWS, 0));
        String later = noticeOf(s.frame(WIDTH, ROWS, 3));
        assertTrue(first.startsWith("Verify and Repair Files ..."), String.valueOf(first));
        assertFalse(first.equals(later));
    }

    // helpers

    private static RepairSession session(UpdaterState state) {
        RepairSession s = new RepairSession(new RepairRegistry());
        s.state(state);
        return s;
    }

    private static UpdaterState withNoOptionalMods() {
        UpdaterState base = RepairSample.state("idle");
        return new UpdaterState(true, base.installed(), base.pinned(), false, base.lastSyncResult(),
                base.lastSyncAt(), base.nowMillis(), false, 0, 0, false, base.notes(), List.of(), null,
                base.serverHost(), base.serverPort(), base.resetTarget());
    }

    private static void select(RepairSession s, String id) {
        Recorder host = new Recorder();
        for (int i = 0; i < s.items().size(); i++) {
            if (s.items().get(s.selection()).id().equals(id)) {
                return;
            }
            s.key(RepairIntent.DOWN, host);
        }
    }

    private static Tone leftToneOf(RepairFrame frame, String label) {
        for (RepairFrame.Row row : frame.rows()) {
            if (row.left().trim().endsWith(label)) {
                return row.leftTone();
            }
        }
        return null;
    }

    private static String noticeOf(RepairFrame frame) {
        String last = "";
        for (RepairFrame.Row row : frame.rows()) {
            if (!row.right().isBlank()) {
                last = row.right();
            }
        }
        return last;
    }

    /** Fake BiosHost that records calls so tests can assert what did or did not run. */
    static final class Recorder implements RepairContext {
        int runs;
        int leaves;
        int closes;
        int stateReads;
        int releaseReads;
        RepairAction action;

        @Override
        public void run(String working, RepairAction action) {
            runs++;
            this.action = action;
        }

        @Override
        public void readState() {
            stateReads++;
        }

        @Override
        public void readReleases() {
            releaseReads++;
        }

        @Override
        public void closeGame() {
            closes++;
        }

        @Override
        public void leave(String notice) {
            leaves++;
        }
    }

    /** Fake engine stub returning canned responses and counting op calls. */
    static final class FakeOps implements RepairOps {
        int repairCode;
        int syncCode;
        int unpinCode;
        int setServerCode;
        String serverHost;
        int serverPort;
        boolean toggleOk = true;
        int repairs;
        int syncs;
        String syncedTag;
        String toggledPath;
        boolean toggledTo;

        @Override
        public UpdaterState read(long nowMillis) {
            return RepairSample.state("idle");
        }

        @Override
        public ReleaseList releases() {
            return new ReleaseList(ReleaseList.LIVE, List.of());
        }

        @Override
        public int repair() {
            repairs++;
            return repairCode;
        }

        @Override
        public int syncNow() {
            syncs++;
            return syncCode;
        }

        @Override
        public int syncTo(String tag) {
            syncedTag = tag;
            return syncCode;
        }

        @Override
        public int unpin() {
            return unpinCode;
        }

        @Override
        public boolean setDisabled(String path, boolean disabled) {
            toggledPath = path;
            toggledTo = disabled;
            return toggleOk;
        }

        @Override
        public java.util.Map<String, Object> factoryReset() {
            return java.util.Map.of(); // No test calls reset here; an empty map matches what an older engine returns.
        }

        @Override
        public int setServer(String host, int port) {
            serverHost = host;
            serverPort = port;
            return setServerCode;
        }
    }
}
