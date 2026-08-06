package com.retro.bamfmenu.early.bios.modes.repair;

import com.retro.bamfmenu.early.bios.gfx.TerminalCanvas;
import com.retro.bamfmenu.early.bios.host.Mode;
import com.retro.bamfmenu.early.bios.host.SetupSizing;
import com.retro.bamfmenu.early.bios.screen.BiosContext;
import com.retro.bamfmenu.early.bios.screen.Screen;
import com.retro.bamfmenu.early.core.asset.Art;
import com.retro.bamfmenu.early.core.repair.api.ReleaseList;
import com.retro.bamfmenu.early.core.repair.api.RepairAction;
import com.retro.bamfmenu.early.core.repair.api.RepairContext;
import com.retro.bamfmenu.early.core.repair.api.RepairOps;
import com.retro.bamfmenu.early.core.repair.api.RepairResult;
import com.retro.bamfmenu.early.core.repair.api.Tone;
import com.retro.bamfmenu.early.core.repair.api.UpdaterState;
import com.retro.bamfmenu.early.core.repair.input.RepairKeys;
import com.retro.bamfmenu.early.core.repair.ops.EngineOps;
import com.retro.bamfmenu.early.core.repair.registry.RepairRegistry;
import com.retro.bamfmenu.early.core.repair.session.RepairSession;
import com.retro.bamfmenu.early.core.repair.view.RepairBox;
import com.retro.bamfmenu.early.core.repair.view.RepairFrame;

import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

// Mode.REPAIR screen for Update and Repair. Runs blocking HTTP/file ops on a single daemon worker thread so the terminal stays responsive; all state lives in RepairSession.
public final class RepairScreen implements Screen, RepairContext {

    private final Queue<Integer> input;
    /* Typed characters, a SEPARATE queue from the keys on purpose: one queue carrying both would
       have to encode which kind each entry is, and the first bug in that scheme types a letter
       every time somebody presses an arrow. */
    private final Queue<Integer> typed;
    /* SETUP's option count, so this box reserves the SAME vertical budget its menu does and crossing
       between the two screens cannot resize the text or move the divider. */
    private final int reservedRows;

    private final RepairOps ops = new EngineOps();
    private final AtomicReference<UpdaterState> readState = new AtomicReference<>();
    private final AtomicReference<ReleaseList> readReleases = new AtomicReference<>();
    // package-private so a test can pose "a result landed while nobody was looking" without a worker race
    final AtomicReference<RepairResult> actionResult = new AtomicReference<>();

    private RepairRegistry registry; // built on the FIRST open, because a boot that never opens this pays nothing
    private ExecutorService worker;  // one daemon thread, kept across opens (an idle thread costs nothing)
    private RepairSession session;
    private BiosContext host;        // the provider's single context instance, for leaving + the header

    public RepairScreen(Queue<Integer> input, Queue<Integer> typed, int reservedRows) {
        this.typed = typed;
        this.input = input;
        this.reservedRows = reservedRows;
    }

    @Override
    public Mode mode() {
        return Mode.REPAIR;
    }

    @Override
    public void onEnter(BiosContext ctx) {
        input.clear();
        host = ctx;
        readState.set(null);
        readReleases.set(null);
        // a result that landed while this screen was closed still gets its say. Nulling it here ate the
        // farewell for an armed factory reset, and the wipe then hit on a later boot with no warning shown
        RepairResult held = actionResult.getAndSet(null);
        if (registry == null) {
            registry = new RepairRegistry();
        }
        session = new RepairSession(registry);
        if (held != null) {
            session.done(held);
        }
        readState(); // the page opens on "Reading the updater ..." and fills in when the worker answers
        ctx.enterMode(Mode.REPAIR);
    }

    @Override
    public void step(BiosContext ctx) {
        host = ctx;
        RepairSession s = session;
        if (s == null) {
            ctx.exitToSetup(0, "");
            return;
        }
        drainWorker(s, ctx);
        s.tick(this); // the farewell countdown, which needs a frame loop and the session has none
        // characters first: a codepoint and the ENTER that commits it can land in the same frame, and
        // draining keys first would run the commit against a field missing its last character
        Integer ch;
        while ((ch = typed.poll()) != null) {
            s.type(ch);
        }
        Integer key;
        while ((key = input.poll()) != null) {
            s.key(RepairKeys.of(key), this);
            if (session == null) {
                return; // leave() ran, this screen is done for now
            }
        }
    }

    /* The worker's three answers, taken on the render thread so the session is only ever touched by
       one thread. The state read also refreshes the clock and the engine's sync flag, which is how the
       single-writer gate stays live instead of frozen at whatever it was when the page opened. */
    private void drainWorker(RepairSession s, BiosContext ctx) {
        UpdaterState fresh = readState.getAndSet(null);
        if (fresh != null) {
            ReleaseList keep = s.state().releases();
            s.state(keep == null ? fresh : fresh.withReleases(keep));
        }
        ReleaseList releases = readReleases.getAndSet(null);
        if (releases != null) {
            s.state(s.state().withReleases(releases));
        }
        RepairResult result = actionResult.getAndSet(null);
        if (result != null) {
            if (result.kind() == RepairResult.Kind.RESTART_REQUIRED) {
                // keys pressed at the "Working ..." screen were aimed at IT; left queued they would take
                // the farewell the same frame it arms, before it draws once (the host hit this live)
                input.clear();
                typed.clear();
            }
            s.done(result);
            readState(); // whatever it did, the snapshot it was built from is now old news
        }
        s.state(s.state().live(System.currentTimeMillis(), ctx.updaterSyncActive()));
    }

    @Override
    public String controlHints(BiosContext ctx) {
        RepairSession s = session;
        return s == null ? "" : s.hints();
    }

    // RepairContext: the session decides, the host does

    @Override
    public void run(String working, RepairAction action) {
        submit(() -> {
            RepairResult result;
            try {
                result = action.run(ops);
            } catch (Throwable t) {
                System.err.println("[bamfmenu] repair action failed: " + t);
                result = RepairResult.noAnswer(); // the ONE sentence for this, so it cannot drift per site
            }
            actionResult.set(result == null ? RepairResult.noAnswer() : result);
        });
    }

    @Override
    public void readState() {
        submit(() -> {
            try {
                readState.set(ops.read(System.currentTimeMillis()));
            } catch (Throwable t) {
                System.err.println("[bamfmenu] repair status read failed: " + t);
                readState.set(UpdaterState.reading()); // the page keeps saying it is reading, never lies
            }
        });
    }

    @Override
    public void readReleases() {
        submit(() -> {
            try {
                readReleases.set(ops.releases());
            } catch (Throwable t) {
                System.err.println("[bamfmenu] repair release list failed: " + t);
                readReleases.set(new ReleaseList(ReleaseList.OFFLINE, java.util.List.of()));
            }
        });
    }

    /**
     * Ends the process, the only op in this window that does. A factory reset is journalled by the time this
     * runs, so the next launch converges from bytes already on disk whatever happens here.
     * exit and not Minecraft.stop(): at BIOS time the game has not started, there is no world open and no
     * Minecraft instance to ask, and this module has no Minecraft classes by design. Status 0 because this is a
     * deliberate quit, and a launcher reading a non-zero code would report a crash the player did not have.
     */
    @Override
    public void closeGame() {
        System.out.println("[bamfmenu] factory reset armed, closing so the next launch can apply it");
        try {
            System.out.flush();
        } catch (Throwable ignored) {
            // never let a flush be the reason the exit below does not happen
        }
        Runtime.getRuntime().halt(0);
    }

    @Override
    public void leave(String notice) {
        BiosContext ctx = host;
        session = null;
        input.clear();
        if (ctx != null) {
            ctx.exitToSetup(0, notice); // row 0 is the entry this window hangs off
        }
    }

    /* One place that touches the executor, so a submit can never be the thing that kills a frame. */
    private void submit(Runnable job) {
        try {
            if (worker == null) {
                worker = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "bamfmenu-repair");
                    t.setDaemon(true); // a hand-off must never wait on this thread to finish
                    return t;
                });
            }
            worker.execute(job);
        } catch (Throwable t) {
            System.err.println("[bamfmenu] repair worker rejected a job: " + t);
            actionResult.set(RepairResult.noAnswer());
        }
    }

    @Override
    public void render(TerminalCanvas c, BiosContext ctx) {
        RepairSession s = session;
        if (s == null) {
            return; // leave() ran earlier this frame; the cleared background is already correct
        }
        // SETUP's own canvas width for a box this tall, asked rather than re-derived: the two screens hand
        // off to each other, so one owner for the widen rule is what keeps the font from jumping on the way
        int logicalW = ctx.biosBodyWidth(reservedRows);
        int logicalH = SetupSizing.bodyHeight(logicalW, c.fbWidth(), c.fbHeight());
        int maxChars = Math.max(40, logicalW / c.charWidth() - 1);
        int lineSpacing = c.lineSpacing();
        int charWidth = c.charWidth();

        c.beginFrameShader(logicalW, logicalH);
        int border = c.cFg();
        int y = ctx.drawBiosHeader(logicalW, logicalH);
        c.text(TerminalCanvas.MARGIN, y, TerminalCanvas.rule(maxChars), border);
        y += lineSpacing;

        int boxTop = y + lineSpacing;
        /* Asked, not assumed: this box GROWS to fill the space, and the control bar draws in its own locked
           canvas, so on a widened body logicalH - lineSpacing sits BELOW the bar's real row and this box
           earned itself an extra interior row that landed on top of the legend (5120x1440). */
        int legendY = ctx.biosControlBarY(logicalW);
        int capacity = SetupSizing.boxCapacity(reservedRows, boxTop, legendY, lineSpacing);
        // pushed every frame rather than pulled by the session: the session has no clock, and the host is
        // already tailing this feed for the boot terminal, so both surfaces read the one source
        s.activity(ctx.updaterActivity());
        RepairFrame frame = s.frame(maxChars, capacity, ctx.frame());
        c.text(TerminalCanvas.MARGIN, y, TerminalCanvas.clip(frame.title(), maxChars), c.cBright());

        int leftX = TerminalCanvas.MARGIN;
        int leftCellX = TerminalCanvas.MARGIN + charWidth;
        int dividerX = TerminalCanvas.MARGIN + (RepairBox.LEFT_INNER + 1) * charWidth;
        int rightCellX = dividerX + charWidth;
        int rightInner = RepairBox.rightInner(maxChars);
        int rightX = TerminalCanvas.MARGIN + (RepairBox.LEFT_INNER + rightInner + 2) * charWidth;

        c.text(leftX, boxTop, frame.topBar(), border);
        for (int r = 0; r < frame.rows().size(); r++) {
            RepairFrame.Row row = frame.rows().get(r);
            int ry = boxTop + (r + 1) * lineSpacing;
            c.text(leftX, ry, "│", border);
            c.text(leftCellX, ry, row.left(), colour(c, row.leftTone()));
            if (row.divider()) {
                c.text(dividerX, ry, "├", border);
                c.text(rightCellX, ry, "─".repeat(rightInner), border);
                c.text(rightX, ry, "┤", border);
            } else {
                c.text(dividerX, ry, "│", border);
                c.text(rightX, ry, "│", border);
                c.text(rightCellX, ry, row.right(), colour(c, row.rightTone()));
            }
        }
        c.text(leftX, boxTop + (frame.rows().size() + 1) * lineSpacing, frame.bottomBar(), border);

        if (frame.prompting()) {
            String[] lines = frame.prompt().toArray(new String[0]);
            int[] colours = new int[lines.length];
            for (int i = 0; i < lines.length; i++) {
                colours[i] = i == lines.length - 1 ? c.cGold() : c.cBright();
            }
            c.popup(logicalW, logicalH, lines, colours);
        }
    }

    /* Tone to the live theme, so "You're Blue Now" recolours this window with everything else. ALERT
       borrows the red the SETUP fault panel already uses. */
    private static int colour(TerminalCanvas c, Tone tone) {
        return switch (tone) {
            case BRIGHT -> c.cBright();
            case DIM -> c.cDim();
            case NOTICE -> c.cGold();
            case ALERT -> Art.LANE_COLORS[3];
            default -> c.cFg();
        };
    }
}
