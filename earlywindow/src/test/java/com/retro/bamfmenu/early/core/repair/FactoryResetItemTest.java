package com.retro.bamfmenu.early.core.repair;

import com.retro.bamfmenu.early.core.repair.items.factory.FactoryResetItem;

import com.retro.bamfmenu.early.core.repair.api.RepairEffect;
import com.retro.bamfmenu.early.core.repair.api.RepairItem;
import com.retro.bamfmenu.early.core.repair.api.RepairResult;
import com.retro.bamfmenu.early.core.repair.api.UpdaterState;
import com.retro.bamfmenu.early.core.repair.view.RepairFrames;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests factory reset prompts and result messages before and after the engine runs. */
class FactoryResetItemTest {

    private static RepairEffect.Act act() {
        RepairItem item = new FactoryResetItem().item();
        assertTrue(item.effect() instanceof RepairEffect.Act);
        return (RepairEffect.Act) item.effect();
    }

    /** Two confirmation dialogs whose text must stay distinct. */
    @Test
    void itAsksTwiceAndTheSecondBoxSaysSomethingNew() {
        List<RepairEffect.Confirm> asks = act().confirms();
        assertEquals(2, asks.size());

        assertEquals("Confirm", asks.get(0).title());
        assertEquals("Are you sure you'd like to Factory Reset? This action will reset all mods, configs and "
                + "BAMFOS resources to release defaults, including configured game options.", asks.get(0).body());
        assertEquals("Yes", asks.get(0).yes());
        assertEquals("No", asks.get(0).no());

        assertEquals("Warning", asks.get(1).title());
        assertEquals("This cannot be undone. Are you sure?", asks.get(1).body());
        assertEquals("Proceed", asks.get(1).yes());
        assertEquals("Cancel", asks.get(1).no());

        assertNotEquals(asks.get(0).body(), asks.get(1).body());
        assertNotEquals(asks.get(0).yes(), asks.get(1).yes());

        // Only the second confirmation dialog should be alert red; the first is a normal prompt.
        assertFalse(asks.get(0).alert());
        assertTrue(asks.get(1).alert());
    }

    /** Help panel must warn about the two non-obvious losses neither confirmation dialog mentions. */
    @Test
    void theHelpNamesWhatTheDialogsCannot() {
        String help = new FactoryResetItem().item().help();
        assertTrue(help.contains("Mods added by you will be deleted"), help);
        assertTrue(help.contains("Added Resource packs are saved"), help);
        assertTrue(help.contains("All edits to BAMFOS resource packs will be lost"), String.valueOf(help));
        assertTrue(help.contains("Worlds"), String.valueOf(help));
    }

    /** Verifies every line in both confirmation dialogs fits within the popup width. */
    @Test
    void everyWarningLineFitsTheBoxItIsDrawnIn() {
        for (RepairEffect.Confirm c : act().confirms()) {
            for (String line : RepairBoxWrap.wrap(c.body())) {
                assertTrue(line.length() <= RepairFrames.MODAL_WIDTH, String.valueOf(line));
            }
        }
    }

    /** Uses the same line wrapping as the renderer so the test matches the actual screen width. */
    private static final class RepairBoxWrap {
        static List<String> wrap(String body) {
            return com.retro.bamfmenu.early.core.repair.view.RepairBox.wrap(body, RepairFrames.MODAL_WIDTH);
        }
    }

    /** Code 0 means reset is armed for next boot (nothing deleted yet), so never say "reset complete". */
    @Test
    void armedIsReportedAsDoneWithTheEnginesOwnSentence() {
        RepairResult r = FactoryResetItem.read(Map.of("code", 0L,
                "message", "reset armed for v0.2.5: 879 files downloaded and 412 to remove. "
                        + "Nothing has changed yet."));
        assertEquals(RepairResult.Kind.RESTART_REQUIRED, r.kind());
        assertTrue(r.message().contains("Nothing has changed yet"), r.message());
        assertFalse(r.message().toLowerCase().contains("complete"), String.valueOf(r.message()));
    }

    /** A healthy installation should never be flagged as broken. */
    @Test
    void theRefusalsAreRefusalsAndNotFailures() {
        for (long code : new long[]{1L, 3L, 5L, 6L}) {
            RepairResult r = FactoryResetItem.read(Map.of("code", code, "message", "not this time"));
            assertEquals(RepairResult.Kind.REFUSED, r.kind(), String.valueOf(code));
        }
        assertEquals(RepairResult.Kind.FAILED,
                FactoryResetItem.read(Map.of("code", 2L, "message", "it broke")).kind());
    }

    /**
     * Must show "reset_target" rather than "version": a pinned install reports its running release
     * under "version" and the actual reset destination under "reset_target".
     */
    @Test
    void theRowPromisesTheVersionTheEngineWouldFetchAndNotTheOneWeAreRunning() {
        UpdaterState pinnedAhead = UpdaterState.of(
                Map.<String, Object>of("version", "v0.2.4", "pinned", "v0.2.6", "reset_target", "v0.2.6"),
                List.of(), "", 0L);
        assertEquals("Resets to v0.2.6", new FactoryResetItem().item().status().line(pinnedAhead));

        UpdaterState stampedNeverSynced = UpdaterState.of(
                Map.<String, Object>of("version", "v0.2.5", "pinned", "", "reset_target", ""),
                List.of(), "", 0L);
        assertEquals("Resets to unknown", new FactoryResetItem().item().status().line(stampedNeverSynced));

        UpdaterState ordinary = UpdaterState.of(
                Map.<String, Object>of("version", "v0.2.6", "pinned", "", "reset_target", "v0.2.6"),
                List.of(), "", 0L);
        assertEquals("Resets to v0.2.6", new FactoryResetItem().item().status().line(ordinary));
    }

    /** Older engine returning no status map must not claim the reset failed, because nothing ran. */
    @Test
    void anOldEngineIsToldItIsOldAndNotThatTheResetFailed() {
        for (Map<String, Object> answer : List.of(Map.<String, Object>of(), Map.<String, Object>of("x", 1L))) {
            RepairResult r = FactoryResetItem.read(answer);
            assertEquals(RepairResult.Kind.REFUSED, r.kind());
            assertTrue(r.message().contains("too old"), r.message());
            assertTrue(r.message().contains("Nothing was changed"), String.valueOf(r.message()));
        }
        assertEquals(RepairResult.Kind.REFUSED, FactoryResetItem.read(null).kind());
        assertTrue(FactoryResetItem.read(null).message().contains("too old"));
    }
}
