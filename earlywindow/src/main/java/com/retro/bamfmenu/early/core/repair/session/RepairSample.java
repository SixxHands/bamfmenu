package com.retro.bamfmenu.early.core.repair.session;

import com.retro.bamfmenu.early.core.repair.api.ReleaseList;
import com.retro.bamfmenu.early.core.repair.api.RepairAction;
import com.retro.bamfmenu.early.core.repair.api.RepairContext;
import com.retro.bamfmenu.early.core.repair.api.RepairItem;
import com.retro.bamfmenu.early.core.repair.api.UpdaterState;
import com.retro.bamfmenu.early.core.repair.input.RepairIntent;
import com.retro.bamfmenu.early.core.repair.registry.RepairRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Posed sessions with real rows and hand-built snapshots for tests and the offline PNG renderer.
 * Lives in main so the offline renderer stays in sync with runtime classes; never called at runtime.
 */
public final class RepairSample {

    /** Sample screen states in display order for offline rendering. */
    public static final String[] SCENARIOS = {
            "reading", "idle", "pinned", "syncing", "working", "working-file", "confirm",
            "reset-confirm", "reset-warning", "reset-farewell",
            "status", "optional", "optional-missing", "notes", "rollback", "rollback-offline",
            "rollback-first", "hostile", "hostile-notes", "editing", "editing-long"};

    /* A fixed clock so two renders of the same scenario are byte-identical. */
    private static final long NOW = 1_780_000_000_000L;
    private static final String VERSION = "v0.2.6";

    private RepairSample() {
    }

    /** The snapshot behind a posed scenario. */
    public static UpdaterState state(String scenario) {
        if ("reading".equals(scenario)) {
            return UpdaterState.reading();
        }
        List<UpdaterState.Mod> mods = new ArrayList<>();
        mods.add(new UpdaterState.Mod("mods/optional/journeymap.jar", false, false, false, ""));
        mods.add(new UpdaterState.Mod("mods/optional/shoulder-surfing.jar", true, false, false, ""));
        mods.add(new UpdaterState.Mod("mods/chipped.jar", true, false, true,
                "kicked by the server: unknown registry entry chipped:acacia_button"));
        if (scenario.startsWith("hostile")) {
            return hostile();
        }
        if ("optional-missing".equals(scenario)) {
            // the player deleted the jar by hand instead of using this page, so NEITHER name is on disk
            mods.set(0, new UpdaterState.Mod("mods/optional/journeymap.jar", false, true, false, ""));
        }
        boolean pinned = "pinned".equals(scenario) || "status".equals(scenario);
        UpdaterState base = new UpdaterState(true, VERSION, pinned ? "v0.2.4" : "", "status".equals(scenario),
                "ok", NOW - 14 * 60_000L, NOW, "syncing".equals(scenario), 1, 1, false,
                "Nether rework lands: ash dunes, quartz shelves, and the new seam block.\n"
                        + "Fixes a crash when a contraption crossed a portal mid-assembly.",
                // Placeholder address since rendered PNGs ship in the README.
                // Reset target follows the engine rule (pin wins over installed).
                List.copyOf(mods), null, "super.secret.ip.for.a.server", 25565,
                pinned ? "v0.2.4" : VERSION);
        return switch (scenario) {
            case "rollback" -> base.withReleases(releases(ReleaseList.LIVE, 5));
            case "rollback-offline" -> base.withReleases(releases(ReleaseList.OFFLINE, 1));
            case "rollback-first" -> base.withReleases(releases(ReleaseList.LIVE, 1));
            default -> base;
        };
    }

    /**
     * Worst-case overflow scenario: long jar names and unbroken URLs that `wrap` cannot split.
     * Exercises clipping in layout tests so borders never get pushed off screen.
     */
    private static UpdaterState hostile() {
        String longName = "Create Deco (NeoForge Edition) Compatibility Patch For Every Compat And Stone Zone 1.21.1-4.2.1.jar";
        List<UpdaterState.Mod> mods = new ArrayList<>();
        mods.add(new UpdaterState.Mod("mods/optional/" + longName, false, false, false, ""));
        mods.add(new UpdaterState.Mod("mods/" + longName, true, false, true,
                "kicked by the server: unknown registry entry createdeco:everycompat/stonezone/"
                        + "very_long_registry_path_that_nobody_expected_to_be_this_long_but_here_we_are"));
        return new UpdaterState(true, "v0.2.6", "v0.2.4-release-candidate-2-hotfix-for-the-hotfix", false,
                "ok", NOW - 3 * 24 * 60 * 60_000L, NOW, false, 1, 1, false,
                "See https://github.com/example/bamfos-pack/blob/main/docs/a-very-long-unbroken-link-that-"
                        + "wrap-cannot-split-because-there-is-not-one-single-space-anywhere-inside-it-at-all"
                        + "-and-it-keeps-going for the full notes.",
                List.copyOf(mods), null,
                // the overflow scenario's job is to be too long for its cell, addresses included, and the
                // reset row inherits the same absurd pin because that is what the engine would target here
                "a-very-long-host-name-that-no-narrow-panel-can-hold.example.invalid", 25599,
                "v0.2.4-release-candidate-2-hotfix-for-the-hotfix");
    }

    /** Builds a sample session by driving the real key handler so the state is always reachable. */
    public static RepairSession session(String scenario) {
        RepairSession s = new RepairSession(new RepairRegistry());
        s.state(state(scenario));
        RepairContext quiet = new Quiet();
        switch (scenario) {
            case "status" -> open(s, quiet, "status");
            case "optional", "optional-missing", "hostile" -> open(s, quiet, "optional");
            case "notes", "hostile-notes" -> open(s, quiet, "notes");
            case "rollback", "rollback-offline", "rollback-first" -> open(s, quiet, "rollback");
            // Poses all three factory-reset modals separately (confirm 1, confirm 2, farewell)
            // so the offline renderer verifies each dialog fits on screen.
            case "reset-confirm" -> {
                select(s, quiet, "factory");
                s.key(RepairIntent.ACTIVATE, quiet);
            }
            case "reset-warning" -> {
                select(s, quiet, "factory");
                s.key(RepairIntent.ACTIVATE, quiet); // box 1
                s.key(RepairIntent.ACTIVATE, quiet); // Second confirmation prompt (irreversible action).
            }
            case "reset-farewell" -> {
                select(s, quiet, "factory");
                // Skip straight to the result since the dummy context never runs actions.
                s.done(new com.retro.bamfmenu.early.core.repair.api.RepairResult(
                        com.retro.bamfmenu.early.core.repair.api.RepairResult.Kind.RESTART_REQUIRED,
                        "reset armed for v0.2.5: 879 files downloaded and 412 to remove. "
                                + "Nothing has changed yet."));
            }
            case "confirm" -> {
                select(s, quiet, "verify");
                s.key(RepairIntent.ACTIVATE, quiet); // stops on the are-you-sure box
            }
            case "working" -> {
                select(s, quiet, "verify");
                s.key(RepairIntent.ACTIVATE, quiet); // the prompt
                s.key(RepairIntent.ACTIVATE, quiet); // yes, and the worker takes it
            }
            case "working-file" -> {
                // Same op a second later once the engine names a file on the bus.
                select(s, quiet, "verify");
                s.key(RepairIntent.ACTIVATE, quiet);
                s.key(RepairIntent.ACTIVATE, quiet);
                s.activity("create-6.0.4.jar   sha mismatch");
            }
            case "editing" -> {
                // Open text field on a partial address; taller than confirm dialogs (6 lines vs 3),
                // so this checks clearance above the control bar.
                select(s, quiet, "server");
                s.key(RepairIntent.ACTIVATE, quiet);
                s.key(RepairIntent.ERASE, quiet); // Delete a digit so the caret isn't at the end of the line.
                "9".codePoints().forEach(s::type);
            }
            case "editing-long" -> {
                // address wider than any panel to verify horizontal scrolling instead of overflowing the modal
                select(s, quiet, "server");
                s.key(RepairIntent.ACTIVATE, quiet);
                "a-really-long-replacement-host-name.someones-vps-provider.example:25599"
                        .codePoints().forEach(s::type);
            }
            default -> {
            }
        }
        return s;
    }

    private static void open(RepairSession s, RepairContext ctx, String id) {
        select(s, ctx, id);
        s.key(RepairIntent.ACTIVATE, ctx);
    }

    private static void select(RepairSession s, RepairContext ctx, String id) {
        List<RepairItem> items = s.items();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id().equals(id)) {
                while (s.selection() != i) {
                    s.key(RepairIntent.DOWN, ctx);
                }
                return;
            }
        }
    }

    private static ReleaseList releases(int code, int count) {
        List<ReleaseList.Release> rows = new ArrayList<>();
        rows.add(new ReleaseList.Release(VERSION, "BAMFOS " + VERSION, "2026-07-20T18:04:00Z", false, true));
        String[] older = {"v0.2.5", "v0.2.4", "v0.2.3", "v0.2.2"};
        String[] days = {"2026-07-11T09:12:00Z", "2026-06-28T22:40:00Z", "2026-06-14T15:03:00Z",
                "2026-05-30T11:55:00Z"};
        for (int i = 0; i < older.length && rows.size() < count; i++) {
            rows.add(new ReleaseList.Release(older[i], "BAMFOS " + older[i], days[i], i == 0, false));
        }
        return new ReleaseList(code, rows);
    }

    /* No-op context so sample sessions don't spawn worker threads or exit the window. */
    private static final class Quiet implements RepairContext {
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

        /** No-op so rendering the exit scenario doesn't terminate the offline renderer process. */
        @Override
        public void closeGame() {
        }
    }
}
