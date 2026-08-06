package com.retro.bamfmenu.early.bios.modes.repair;

import com.retro.bamfmenu.early.bios.screen.BiosContext;
import com.retro.bamfmenu.early.core.repair.api.RepairResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests Mode.REPAIR re-entry by populating the result atomic directly without a worker thread. */
class RepairScreenTest {

    /**
     * Regression: if RESTART_REQUIRED from a factory reset arrived while off-screen, onEnter used to
     * clear it when creating a new session, preventing the restart warning and auto-close.
     */
    @Test
    void aResultThatLandedWhileTheWindowWasClosedStillArmsTheFarewell() {
        Queue<Integer> keys = new ConcurrentLinkedQueue<>();
        Queue<Integer> typed = new ConcurrentLinkedQueue<>();
        RepairScreen screen = new RepairScreen(keys, typed, 9);
        BiosContext ctx = quiet();

        screen.actionResult.set(new RepairResult(RepairResult.Kind.RESTART_REQUIRED, "reset armed"));
        screen.onEnter(ctx);
        screen.step(ctx); // First frame should display the pending restart notice with a fresh countdown.
        assertTrue(screen.controlHints(ctx).contains("Closing in"), String.valueOf(screen.controlHints(ctx)));
    }

    /* Use a Proxy returning default values instead of writing dozens of unused BiosContext stubs.
       This path only calls enterMode in onEnter and updaterSyncActive in step. */
    private static BiosContext quiet() {
        return (BiosContext) Proxy.newProxyInstance(BiosContext.class.getClassLoader(),
                new Class<?>[]{BiosContext.class}, (proxy, method, args) -> {
                    Class<?> r = method.getReturnType();
                    if (r == boolean.class) {
                        return false;
                    }
                    if (r == int.class) {
                        return 0;
                    }
                    if (r == long.class) {
                        return 0L;
                    }
                    if (r == double.class) {
                        return 0d;
                    }
                    if (r == String.class) {
                        return "";
                    }
                    return null;
                });
    }
}
