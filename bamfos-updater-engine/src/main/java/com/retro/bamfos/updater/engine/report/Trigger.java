package com.retro.bamfos.updater.engine.report;

// Priority-ordered trouble-report triggers (enum declaration order is priority; at most one report per boot).
enum Trigger {

    NO_ENGINE("no-engine",
            "The updater could not load its engine, so your pack was not checked for updates. "
                    + "The game booted anyway."),

    CORE_REFUSED("core-refused",
            "A component of the updater failed to start twice in a row, so this install rolled back to an "
                    + "older copy. The game booted anyway."),

    ENGINE_REFUSED("engine-refused",
            "The updater rolled its engine back twice and rejected it, so it is running an older engine "
                    + "until the pack owner publishes a new one."),

    // CoreCargo.write only refuses generations listed in core-rejected (cleared by repair); any newer
    // published generation still installs normally.
    FLOOR_ONLY("floor-only",
            "This install rejected every updater update and fell back to the bundled copy. It will accept "
                    + "the next new release, but will not retry previously rejected ones."),

    HELD_SETTLED("held-settled",
            "The updater could not replace some files after all retries, so your pack is partly updated."),

    // Matches STREAK_TO_REPORT in Triggers: 3+ outright failures with no success in between (offline,
    // rate-limited, and no-release boots aren't counted). Update if that constant changes.
    FAILED_STREAK("failed-streak",
            "The updater failed three or more times in a row on this machine with no successful sync in "
                    + "between. This points to a local file issue or a broken release rather than a connection drop.");

    private final String word;
    private final String whyLine;

    Trigger(String word, String whyLine) {
        this.word = word;
        this.whyLine = whyLine;
    }

    /** Identifier written to the fault row and used as part of the dedupe fingerprint. */
    String word() {
        return word;
    }

    /** Short summary line shown at the top of the report for the player. */
    String whyLine() {
        return whyLine;
    }
}
