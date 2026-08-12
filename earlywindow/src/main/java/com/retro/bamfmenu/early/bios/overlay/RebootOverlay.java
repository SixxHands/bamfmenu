package com.retro.bamfmenu.early.bios.overlay;

import com.retro.bamfmenu.early.bios.gfx.TerminalCanvas;
import com.retro.bamfmenu.early.bios.host.Mode;
import com.retro.bamfmenu.early.bios.screen.BiosContext;
import com.retro.bamfmenu.early.bios.screen.Overlay;
import com.retro.bamfmenu.early.core.repair.api.RepairAction;
import com.retro.bamfmenu.early.core.repair.api.RepairContext;
import com.retro.bamfmenu.early.core.repair.api.RepairEffect;
import com.retro.bamfmenu.early.core.repair.api.RepairResult;
import com.retro.bamfmenu.early.core.repair.api.UpdaterState;
import com.retro.bamfmenu.early.core.repair.input.RepairKeys;
import com.retro.bamfmenu.early.core.repair.registry.RepairRegistry;
import com.retro.bamfmenu.early.core.repair.session.RepairSession;
import com.retro.bamfmenu.early.core.repair.view.RepairFrames;

import java.util.List;
import java.util.Queue;
import java.util.function.LongSupplier;

/* Shown on boot when the updater staged files that need a restart to take effect, so players don't boot
 * into stale jars and get kicked by channel mismatch. Waits for POST so we never interrupt someone
 * playing DOOM in the BIOS. */
public final class RebootOverlay implements Overlay, RepairContext {

    /* How long active may hold the hand-off, counted from the moment the box goes up. The loop at the
       other end spins on the MAIN thread inside Minecraft's constructor, so a render thread that wedges
       mid-countdown would pin the boot with no key left that can end it. A farewell that broke has to boot
       stale, because never booting is the worse bug. */
    private static final long GIVE_UP_NANOS = 15_000_000_000L; // the 10s countdown plus 5s of slack

    /**
     * How long the hold waits for the render thread to arm the box, BEFORE the countdown owns the deadline.
     * The gap this closes. The hold used to spin on active, which only turns true inside arm on the
     * render thread. The main thread sets handingOff and evaluates the loop on the very next statement, so at
     * POST, where nothing else keeps, active was still false, the loop body never ran once, and the render
     * thread was stopped before the countdown got a single one of its ~200 frames. The box was inert on the
     * ONE path it exists for, and every test passed because they call handingOff then step on one thread, an
     * ordering production cannot produce. Found by review, not by the suite.
     * Why two deadlines and not one. Holding on the INTENT means a boot that can never draw now waits
     * too, so that wait has to be short: 2s buys the render thread ~40 frames at its 50ms tick to arm in, and
     * a box that has not armed by then is one nobody is going to see. Once arm runs it restamps the
     * deadline to GIVE_UP_NANOS and the countdown gets its full run.
     */
    private static final long ARM_GRACE_NANOS = 2_000_000_000L;

    private final Queue<Integer> input;
    private final LongSupplier nanos;
    private final Runnable exit;
    /* Who touches what. `pending` rides in from the boot feed on the render thread and `handingOff` comes the
       other way from the main thread, once. `pending`, `done`, `active` and `deadline` are all read back out
       by the main thread, in the hold loop and the key callback, which is the same volatile-mirror shape the
       DEL gate already uses for the engine's sync flag. `pending` and `done` are volatile for holds() alone:
       they were render-only until the hold moved onto the intent. */
    private volatile boolean pending;
    private volatile boolean handingOff;
    private volatile boolean active;
    /** Restamped ONCE by arm(): ARM_GRACE_NANOS while we wait to arm, GIVE_UP_NANOS once the box is up. */
    private volatile long deadline;

    private RepairSession session;
    private long armBase;     // the clock reading the box went up at, so millis() below starts from 1
    private boolean composed; // the box's lines have been built once, so a close can never beat the draw
    private volatile boolean done; // closed or stood down, either way this surface is finished for the boot

    /**
     * The shipped door: the host passes the quit it wants taken, because only the host keeps the window whose
     * should-close flag carries the request out to Minecraft. The clock stays ours.
     * There is deliberately NO convenience ctor that supplies its own exit. The one that used to be here
     * defaulted to Runtime.getRuntime.halt(0), which reaches neither a shutdown hook nor Minecraft.stop,
     * and that is exactly the bug that had Crash Assistant filing a crash report for a quit the player chose.
     * A surface that cannot name who takes the quit has no business ending anyone's game.
     */
    public RebootOverlay(Queue<Integer> input, Runnable exit) {
        this(input, System::nanoTime, exit);
    }

    /** The posed-clock, posed-exit door: this thing counts on a clock and then ends the process, and a test
     *  can have neither for real (RepairSession's own second ctor exists for the same reason). */
    RebootOverlay(Queue<Integer> input, LongSupplier nanos, Runnable exit) {
        this.input = input;
        this.nanos = nanos;
        this.exit = exit;
    }

    /** The engine's PENDING event, mirrored off the boot feed. Render thread, every frame. */
    public void pending(boolean staged) {
        pending = staged;
    }

    /**
     * FML has asked for the window, so the boot has nothing left to do. Main thread, once.
     * The deadline is stamped HERE and not in arm(), because the hold below waits on the intent to arm rather
     * than on the armed box, and a wait with no deadline yet is a wait with no way out.
     */
    public void handingOff() {
        try {
            deadline = nanos.getAsLong() + ARM_GRACE_NANOS;
        } catch (Throwable clockGone) {
            done = true; // no clock, no bounded wait, so never hold at all
        }
        handingOff = true; // LAST, so holds() never sees the intent before the deadline that bounds it
    }

    /**
     * Whether the hand-off should wait, read by the main thread every 16ms.
     * This and NOT active: active cannot be true until the render thread has run a frame, and the main
     * thread evaluates the loop one statement after setting handingOff, so holding on active held nothing.
     * Bounded by `deadline` in both phases, so the worst case is a boot that starts late, never one that
     * does not start.
     */
    public boolean holds() {
        try {
            return handingOff && pending && !done
                    && nanos.getAsLong() - deadline < 0; // subtraction, the overflow-safe nanoTime read
        } catch (Throwable t) {
            return false; // same rule as active() below: answer the main thread, never throw into it
        }
    }

    @Override
    public boolean active() {
        try {
            return active && nanos.getAsLong() - deadline < 0; // subtraction, the overflow-safe nanoTime read
        } catch (Throwable t) {
            // the ONE method the main thread reads inside Minecraft's constructor, so it answers rather
            // than throwing into the hold loop or the GLFW key callback. The render thread's own step()
            // stands the box down and says why
            return false;
        }
    }

    @Override
    public void step(BiosContext ctx) {
        if (done) {
            return;
        }
        try {
            if (!active) {
                arm(ctx);
                return; // the box draws THIS frame; counting it down before then is the bug being closed
            }
            if (nanos.getAsLong() - deadline >= 0) {
                standDown("reboot notice did not finish in time, continuing the boot");
                return;
            }
            if (!composed) {
                return;
            }
            // the countdown reads the snapshot's clock, so the host has to keep stamping it: without this
            // the bar sits on "Closing in 10s" forever and the hold rides its give-up deadline instead
            session.state(session.state().live(millis(), false));
            session.tick(this);
            Integer key;
            while ((key = input.poll()) != null) {
                session.key(RepairKeys.of(key), this);
            }
        } catch (Throwable t) {
            standDown("reboot notice failed, continuing the boot: " + t);
        }
    }

    /* Opens the box, once, when the boot is out of things to do and the engine staged something. Held back
       while the player is anywhere else in the BIOS: the hold loop is already waiting on holdsHandoff in
       SETUP and in every game, so waiting for POST costs nothing, and a ten-second countdown over
       somebody's DOOM session would end it for them.
       pendingSwaps is deliberately shim-inclusive (the host's call): if a swapped shim IS the update, it is
       the one most worth applying, so do not "fix" this into ignoring a self-only stage. */
    private void arm(BiosContext ctx) {
        if (!pending || !handingOff || ctx.mode() != Mode.POST
                || ctx.volumeMenuOpen() || ctx.pauseMenuOpen()) {
            return;
        }
        armBase = nanos.getAsLong(); // read BEFORE millis(), which is measured from it
        // the EXPLICIT empty list: the no-arg ctor walks the jar, class-loads every repair row and prints
        // its count into the boot log, all for a menu this box never shows
        RepairSession s = new RepairSession(new RepairRegistry(List.of()), nanos);
        s.state(UpdaterState.reading().live(millis(), false));
        // ofSync(0) and NOT a hand-rolled RESTART_REQUIRED: the result's own cause is what makes the box
        // read "to apply the update", and a result carrying none blames the factory reset instead
        s.done(RepairResult.ofSync(0));
        session = s;
        // the restamp: handingOff() only bought ARM_GRACE_NANOS to get here, the countdown needs its own run
        deadline = nanos.getAsLong() + GIVE_UP_NANOS;
        active = true; // written LAST, because the main thread acts on it the moment it turns true
    }

    /* The countdown's clock: monotonic, and measured from the arm so it can never read zero or below.
       RepairSession spends nowMillis on nothing but deltas, so nanoTime is the better source there, a clock
       correction mid-farewell cannot stretch the ten seconds or skip past them. It is ALSO why the bias
       matters: that class reads closeAt > 0 as "a box is up", and nanoTime is allowed to be negative, so a
       raw reading can arm a farewell the session then denies having and the box never draws. */
    private long millis() {
        return (nanos.getAsLong() - armBase) / 1_000_000L + 1L;
    }

    @Override
    public void render(TerminalCanvas c, BiosContext ctx) {
        if (done) {
            return;
        }
        try {
            String[] lines = compose();
            if (lines.length == 0) {
                return;
            }
            int[] colours = new int[lines.length];
            for (int i = 0; i < lines.length; i++) {
                colours[i] = i == lines.length - 1 ? c.cGold() : c.cBright();
            }
            c.beginFrameShader(c.logicalW(), c.logicalH());
            c.popup(c.logicalW(), c.logicalH(), lines, colours);
        } catch (Throwable t) {
            standDown("reboot notice draw failed, continuing the boot: " + t);
        }
    }

    /* The box's lines, and the flag that says they exist: the countdown refuses to close the game until this
       has answered once, so a farewell nobody could see never ends a player's boot. RepairFrames composes
       them, wrapping and the single-option "[ Ok ]" rule included, because a second owner for those is two
       boxes that slowly stop matching. Package-private: a test has no GL to draw with, and this is the last
       step before the pixels. */
    String[] compose() {
        RepairSession s = session;
        if (s == null) {
            return new String[0];
        }
        RepairEffect.Confirm box = s.confirm();
        if (box == null) {
            return new String[0];
        }
        List<String> lines = RepairFrames.modalLines(box);
        composed = !lines.isEmpty();
        return lines.toArray(new String[0]);
    }

    /** The countdown row for the shared bottom bar, "" when no box is up. */
    public String hints() {
        try {
            RepairSession s = session;
            return active() && s != null ? s.hints() : "";
        } catch (Throwable t) {
            standDown("reboot notice bar failed, continuing the boot: " + t);
            return "";
        }
    }

    @Override
    public void closeGame() {
        done = true;
        System.out.println("[bamfmenu] update staged, closing so the next launch can apply it");
        try {
            System.out.flush();
        } catch (Throwable ignored) {
            // never let a flush be the reason the exit below does not happen
        }
        exit.run();
    }


    /* The four the farewell never reaches. leave() in particular has to stay empty: at POST there is no
       SETUP to fall back to, so honouring it would drop the box and hand off to a Minecraft still holding
       the stale jars, which is the bug. */
    @Override
    public void run(String working, RepairAction action) {
    }

    @Override
    public void readState() {
    }

    @Override
    public void readReleases() {
    }

    @Override
    public void leave(String notice) {
    }

    /* The one way out that is not the close: say so once, drop the box, and let the boot finish exactly as
       it did before any of this existed. */
    private void standDown(String why) {
        done = true;
        active = false;
        System.err.println("[bamfmenu] " + why);
    }
}
