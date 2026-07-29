package com.retro.bamfmenu.early.core.repair;

import com.retro.bamfmenu.early.core.repair.items.factory.FactoryResetItem;

import com.retro.bamfmenu.early.core.repair.api.RepairEffect;
import com.retro.bamfmenu.early.core.repair.api.RepairItem;
import com.retro.bamfmenu.early.core.repair.api.RepairResult;
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
        assertEquals("Are you sure? This action will reset all configs to release defaults, and delete your "
                + "configured game options.", asks.get(0).body());
        assertEquals("Yes", asks.get(0).yes());
        assertEquals("No", asks.get(0).no());

        assertEquals("Warning", asks.get(1).title());
        assertEquals("This cannot be undone. Are you sure?", asks.get(1).body());
        assertEquals("Proceed", asks.get(1).yes());
        assertEquals("Cancel", asks.get(1).no());

        assertNotEquals(asks.get(0).body(), asks.get(1).body());
        assertNotEquals(asks.get(0).yes(), asks.get(1).yes());
    }

    /** Help panel must warn about the two non-obvious losses neither confirmation dialog mentions. */
    @Test
    void theHelpNamesWhatTheDialogsCannot() {
        String help = new FactoryResetItem().item().help();
        assertTrue(help.contains("Mods you added yourself are deleted"), help);
        assertTrue(help.contains("Resource packs you added survive"), help);
        assertTrue(help.contains("came with BAMFOS"), String.valueOf(help));
        assertTrue(help.contains("worlds"), String.valueOf(help));
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

    /** Older engine returning no status map must not claim the reset failed, because nothing ran. */
    @Test
    void anOldEngineIsToldItIsOldAndNotThatTheResetFailed() {
        for (Map<String, Object> answer : List.of(Map.<String, Object>of(), Map.<String, Object>of("x", 1L))) {
            RepairResult r = FactoryResetItem.read(answer);
            assertTrue(r.message().contains("too old"), r.message());
            assertTrue(r.message().contains("Nothing was changed"), String.valueOf(r.message()));
        }
        assertTrue(FactoryResetItem.read(null).message().contains("too old"));
    }
}
