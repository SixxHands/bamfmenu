package com.retro.bamfmenu.early.core.repair.session;

import com.retro.bamfmenu.early.core.repair.api.PageRow;
import com.retro.bamfmenu.early.core.repair.api.RepairContext;
import com.retro.bamfmenu.early.core.repair.api.RepairEffect;
import com.retro.bamfmenu.early.core.repair.api.RepairGate;
import com.retro.bamfmenu.early.core.repair.api.RepairItem;
import com.retro.bamfmenu.early.core.repair.api.RepairPage;
import com.retro.bamfmenu.early.core.repair.api.RepairResult;
import com.retro.bamfmenu.early.core.repair.api.UpdaterState;
import com.retro.bamfmenu.early.core.repair.input.RepairIntent;
import com.retro.bamfmenu.early.core.repair.input.TextEntry;
import com.retro.bamfmenu.early.core.repair.registry.RepairRegistry;
import com.retro.bamfmenu.early.core.repair.view.RepairBox;
import com.retro.bamfmenu.early.core.repair.view.RepairFrame;
import com.retro.bamfmenu.early.core.repair.view.RepairFrames;

import java.util.List;

/**
 * Pure state machine for the repair window (selection, pages, modals, input; no GL or threads).
 * Shares one key ladder so ESC cannot exit mid-write; delegates ops to RepairContext and layout to RepairFrames.
 */
public final class RepairSession {

    private static final String HINTS_LIST = "UP/DOWN  Select      ENTER  Choose      ESC  Back      F2  Volume";
    /** Auto-close timeout for the farewell dialog (10s): long enough to read, short enough if AFK. */
    static final long FAREWELL_MS = 10_000L;
    static final String FAREWELL_TITLE = "Restart Required";
    static final String FAREWELL_BODY = "A restart is needed to apply the Factory Reset. Your game will now "
            + "close. Press any key to continue.";

    private static final String HINTS_EDIT = "TYPE  Edit      LEFT/RIGHT  Move      ENTER  Save      ESC  Cancel";
    /* Names the two keys that really are locked, because the arrows still move on purpose (a frozen-looking
       window reads as a hung one) and the provider takes F2 before this screen sees it, so a bar claiming
       every key is locked is contradicted by the next thing a player presses. 's rule with the
       door already open: the BIOS stays up, actions do not. */
    private static final String HINTS_BUSY =
            "Working ...      ENTER and ESC are locked until the engine answers      F2  Volume";

    private final List<RepairItem> items;
    private UpdaterState state = UpdaterState.reading();
    private int sel;

    private RepairPage page;
    private int pageSel;
    private int pageTop;
    /* Last rendered panel width; updated by frame() so keypress index lookups match wrapped rows on screen. */
    private int panelWidth = 60;

    private RepairEffect.Act pending; // the are-you-sure box, null when none is up
    /** Index of the active confirmation prompt for `pending` (some actions like reset ask twice
     *  with different messages, so a boolean isn't enough). */
    private int promptStep;
    private TextEntry editing;              // the open field, null when nothing is being typed
    private RepairEffect.Edit editingEffect; // action to run when ENTER submits the active text field
    private String working = "";      // non-empty while the worker keeps an action
    /* Active file status pushed from the screen's frame loop off the boot bus feed; cleared when the op ends. */
    private String activity = "";
    private RepairResult last;        // the notice line, null until something has happened
    /* Farewell dialog deadline in wall-clock millis (0 when inactive), driven by the per-frame snapshot clock. */
    private long closeAt;

    public RepairSession(RepairRegistry registry) {
        this.items = registry.items();
    }

    /** The rows this window found, in menu order. */
    public List<RepairItem> items() {
        return items;
    }

    /** Current snapshot used by gates and status lines. */
    public UpdaterState state() {
        return state;
    }

    /** Publishes a fresh snapshot from the worker. */
    public void state(UpdaterState next) {
        this.state = next == null ? UpdaterState.reading() : next;
    }

    /** True while a background action is running (all keys except arrow keys are ignored). */
    public boolean busy() {
        return !working.isEmpty();
    }

    /* Marks the worker busy with `label` so the panel shows what is running. */
    private void begin(String label) {
        working = label == null ? "" : label;
    }

    /** Updates the live progress line from the engine bus each frame (empty when idle). */
    public void activity(String line) {
        activity = line == null ? "" : line;
    }

    /** Applies the worker result, clears the busy state, and displays the returned status line. */
    public void done(RepairResult result) {
        if (result != null && result.kind() == RepairResult.Kind.RESTART_REQUIRED) {
            // Start the exit countdown here rather than in the item since this session displays the countdown.
            closeAt = state.nowMillis() + FAREWELL_MS;
        }
        working = "";
        activity = ""; // Clear any leftover progress text once the operation finishes.
        last = result;
    }

    /** Routes a typed codepoint to the open text field; separate from key() so printable chars never hit menu intents. */
    public void type(int codepoint) {
        if (!busy() && editing != null) {
            editing.type(codepoint);
        }
    }

    /**
     * Handles a mapped key intent in priority order:
     * busy lockout first, then open field, confirm prompt, sub-page, and finally the main list.
     */
    public void key(RepairIntent intent, RepairContext ctx) {
        if (busy()) {
            // Keep arrow navigation working so the window doesn't feel frozen, but block starting actions
            // or exiting while the engine is still staging files.
            if (intent == RepairIntent.ACTIVATE || intent == RepairIntent.BACK) {
                last = RepairResult.locked();
            } else {
                moveSelection(intent);
            }
            return;
        }
        if (closeAt > 0) {
            // Any key dismisses the farewell dialog, matching its "press any key" prompt.
            ctx.closeGame();
            return;
        }
        if (editing != null) {
            switch (intent) {
                case ACTIVATE -> {
                    // Re-check the gate on submit in case a sync started while the user was typing.
                    String typed = editing.value().trim();
                    RepairEffect.Edit edit = editingEffect;
                    if (typed.isEmpty()) {
                        return; // Ignore ENTER on an empty text field.
                    }
                    editing = null;
                    editingEffect = null;
                    start(new RepairEffect.Act(edit.working(), "", ops -> edit.action().run(ops, typed)), ctx);
                }
                case BACK -> {
                    editing = null;
                    editingEffect = null;
                    last = new RepairResult(RepairResult.Kind.REFUSED, "Cancelled.");
                }
                case LEFT -> editing.left();
                case RIGHT -> editing.right();
                case ERASE -> editing.erase();
                default -> {
                }
            }
            return;
        }
        if (pending != null) {
            switch (intent) {
                case ACTIVATE -> {
                    // Advance through each confirm dialog in order; run the action only after the last one.
                    if (promptStep + 1 < pending.confirms().size()) {
                        promptStep++;
                        return;
                    }
                    RepairEffect.Act act = pending;
                    pending = null;
                    promptStep = 0;
                    start(act, ctx);
                }
                case BACK -> {
                    // ESC cancels the entire confirm sequence instead of stepping back one dialog.
                    pending = null;
                    promptStep = 0;
                    last = new RepairResult(RepairResult.Kind.REFUSED, "Cancelled.");
                }
                default -> {
                }
            }
            return;
        }
        switch (intent) {
            case UP, DOWN -> moveSelection(intent);
            case ACTIVATE -> activate(ctx);
            case BACK -> {
                if (page != null) {
                    page = null; // one step back, out of the page but not out of the window
                    last = null;
                } else {
                    ctx.leave("");
                }
            }
            default -> {
            }
        }
    }

    /* Moves the cursor on either the main list or the open page. If a page has no selectable rows
       (like release notes), UP/DOWN scrolls it instead. */
    private void moveSelection(RepairIntent intent) {
        if (intent != RepairIntent.UP && intent != RepairIntent.DOWN) {
            return;
        }
        int step = intent == RepairIntent.UP ? -1 : 1;
        if (page == null) {
            if (!items.isEmpty()) {
                sel = (sel + step + items.size()) % items.size();
                last = null; // Clear the previous status message when moving to a different row.
            }
            return;
        }
        List<PageRow> rows = page.rows(state, panelWidth);
        if (!RepairFrames.hasPicks(rows)) {
            pageTop = Math.max(0, pageTop + step);
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            pageSel = (pageSel + step + rows.size()) % rows.size();
            if (rows.get(pageSel).selectable()) {
                return;
            }
        }
    }

    private void activate(RepairContext ctx) {
        if (!state.loaded()) {
            // Don't run anything before the first snapshot loads, otherwise gates would check empty data
            // and show bogus refusals like "no optional mods in this release".
            last = new RepairResult(RepairResult.Kind.REFUSED, "Reading the updater ...");
            return;
        }
        if (page != null) {
            RepairEffect.Act act = page.activate(pageSel, state, panelWidth);
            if (act == null) {
                return;
            }
            // Enforce the single-writer lock here for both the main menu and subpages so individual pages
            // don't have to check it themselves.
            String locked = RepairGate.FILE_SAFE.whyNot(state);
            if (!locked.isEmpty()) {
                last = new RepairResult(RepairResult.Kind.REFUSED, locked);
                return;
            }
            offer(act, ctx);
            return;
        }
        if (items.isEmpty()) {
            return;
        }
        RepairItem item = items.get(sel);
        String why = item.whyNot(state);
        if (!why.isEmpty()) {
            last = new RepairResult(RepairResult.Kind.REFUSED, why); // Show the gate refusal reason directly.
            return;
        }
        switch (item.effect()) {
            case RepairEffect.Page p -> {
                page = p.page();
                pageSel = RepairFrames.firstPick(p.page().rows(state, panelWidth));
                pageTop = 0;
                last = null;
                if (p.page().needsReleases() && state.releases() == null) {
                    ctx.readReleases();
                }
            }
            case RepairEffect.Act a -> offer(a, ctx);
            case RepairEffect.Edit e -> {
                // Check the gate before opening the text field as well as on ENTER so players don't
                // type an address only to have it rejected afterward.
                String locked = RepairGate.FILE_SAFE.whyNot(state);
                if (!locked.isEmpty()) {
                    last = new RepairResult(RepairResult.Kind.REFUSED, locked);
                    return;
                }
                editing = new TextEntry(e.initial().apply(state), TextEntry.MAX);
                editingEffect = e;
                last = null;
            }
        }
    }

    /* Show confirmation prompts if configured; otherwise run the action immediately. */
    private void offer(RepairEffect.Act act, RepairContext ctx) {
        if (act.confirms().isEmpty()) {
            start(act, ctx);
        } else {
            pending = act;
            promptStep = 0;
        }
    }

    private void start(RepairEffect.Act act, RepairContext ctx) {
        // The last gate before the hand-off, because the ones above ran before the prompt went up and a player
        // reading an are-you-sure box is a player the engine can start writing behind (single
        // writer). InstanceLock is NOT the backstop: it reads our own pid as a dead holder, deletes the
        // live lock file and hands out a second lock inside the same JVM.
        String locked = RepairGate.FILE_SAFE.whyNot(state);
        if (!locked.isEmpty()) {
            last = new RepairResult(RepairResult.Kind.REFUSED, locked);
            return;
        }
        begin(act.working());
        last = null;
        ctx.run(act.working(), act.action());
    }

    /** Advances the farewell countdown from the screen's frame loop; no-op unless armed. */
    public void tick(RepairContext ctx) {
        if (closeAt > 0 && state.nowMillis() >= closeAt) {
            ctx.closeGame();
        }
    }

    /** Seconds left before the game closes itself, 0 when no farewell is up. */
    public int closingIn() {
        return closeAt <= 0 ? 0 : (int) Math.max(0, (closeAt - state.nowMillis() + 999) / 1000);
    }

    /** The bottom control bar for wherever the window currently is. */
    public String hints() {
        if (busy()) {
            return HINTS_BUSY;
        }
        if (editing != null) {
            return HINTS_EDIT;
        }
        if (closeAt > 0) {
            return "Closing in " + closingIn() + "s      Press any key to close now";
        }
        if (pending != null) {
            // Use this prompt's own button labels (e.g. Proceed/Cancel on reset step 2) so the footer matches.
            RepairEffect.Confirm c = confirm();
            return "ENTER  " + c.yes() + "      ESC  " + c.no();
        }
        return page != null ? page.hints() : HINTS_LIST;
    }

    /**
     * Builds the current frame via RepairFrames (`maxChars` cols, `interiorRows` rows, `tick` for spinner).
     * Saves the clamped page cursor back to the session so ENTER stays aligned after resizes or refreshes.
     */
    public RepairFrame frame(int maxChars, int interiorRows, int tick) {
        panelWidth = RepairBox.rightInner(maxChars);
        RepairFrames.View view = new RepairFrames.View(items, sel, state, page, pageSel, pageTop,
                working, activity, last, hints(), confirm(), editTitle(), editField());
        RepairFrames.Built built = RepairFrames.build(view, maxChars, interiorRows, tick);
        pageSel = built.pageSel();
        pageTop = built.pageTop();
        return built.frame();
    }

    // the bits a test or an offline render needs to see without going through the frame

    /** Index of the currently highlighted menu row. */
    public int selection() {
        return sel;
    }

    /** The open page, null while the menu list is up. */
    public RepairPage openPage() {
        return page;
    }

    /** Active confirmation dialog, or null if none is open. */
    public RepairEffect.Confirm confirm() {
        if (closeAt > 0) {
            // one option, because there is no second answer: the reset is already journalled and the next boot
            // applies it whether this box closes politely or gets killed
            return new RepairEffect.Confirm(FAREWELL_TITLE, FAREWELL_BODY, "Ok", "Ok");
        }
        return pending == null || promptStep >= pending.confirms().size()
                ? null : pending.confirms().get(promptStep);
    }

    /** Heading for the open text field, or "" when no field is active. */
    public String editTitle() {
        return editingEffect == null ? "" : editingEffect.title();
    }

    /** True while a text entry field is open. */
    public boolean editingField() {
        return editing != null;
    }

    /** Current text field value without the caret character. */
    public String editValue() {
        return editing == null ? "" : editing.value();
    }

    /* Renders the text field with its caret, sized to the panel width minus modal border and padding
       so the caret never clips under the frame on narrow windows. */
    private String editField() {
        return editing == null ? "" : editing.visible(Math.max(12, panelWidth - 8));
    }

    /** Most recent status line shown in the window, or "" if none. */
    public String lastMessage() {
        return last == null ? "" : last.message();
    }
}
