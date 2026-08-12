package com.retro.bamfmenu.early.bios.modes.repair;

import com.retro.bamfmenu.early.bios.screen.BiosContext;
import com.retro.bamfmenu.early.core.repair.api.RepairResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /**
     * Verifies farewell exits through RepairHost instead of Runtime.halt(0),
     * which bypassed shutdown hooks and made Crash Assistant flag normal quits as crashes.
     */
    @Test
    void theFarewellAsksTheHostToQuitAndNeverEndsTheProcessItself() {
        boolean[] asked = {false};
        BiosContext ctx = watching(asked);
        RepairScreen screen = new RepairScreen(new ConcurrentLinkedQueue<>(), new ConcurrentLinkedQueue<>(), 9);
        screen.onEnter(ctx);

        screen.closeGame(); // reaching the next line at all is half the assertion

        assertTrue(asked[0]);
    }

    /**
     * Session calls closeGame every frame after expiry; RepairHost must trigger quit only once
     * instead of repeating every frame after the dialog closes.
     */
    @Test
    void theQuitIsAskedForExactlyOnceHoweverManyFramesRun() {
        int[] asks = {0};
        BiosContext ctx = counting(asks);
        RepairScreen screen = new RepairScreen(new ConcurrentLinkedQueue<>(), new ConcurrentLinkedQueue<>(), 9);
        screen.onEnter(ctx);

        for (int frame = 0; frame < 120; frame++) {
            screen.closeGame();
        }

        assertEquals(1, asks[0]);
    }

    /* quiet(), counting how many times quit was requested. */
    private static BiosContext counting(int[] asks) {
        BiosContext quiet = quiet();
        return (BiosContext) Proxy.newProxyInstance(BiosContext.class.getClassLoader(),
                new Class<?>[]{BiosContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("requestGameClose")) {
                        asks[0]++;
                        return null;
                    }
                    return method.invoke(quiet, args);
                });
    }

    /* quiet(), plus whether quit was requested. */
    private static BiosContext watching(boolean[] asked) {
        BiosContext quiet = quiet();
        return (BiosContext) Proxy.newProxyInstance(BiosContext.class.getClassLoader(),
                new Class<?>[]{BiosContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("requestGameClose")) {
                        asked[0] = true;
                        return null;
                    }
                    return method.invoke(quiet, args);
                });
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
