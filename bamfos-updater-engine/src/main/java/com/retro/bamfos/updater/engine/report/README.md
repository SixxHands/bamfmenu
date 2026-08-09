# engine/report

Last-resort updater diagnostic written into `crash-reports/bamfos-updater-<stamp>.txt` when the updater hits a state it cannot fix on its own.

## Design rules

1. **No free text off disk ever reaches the report.** `ReportBody` takes typed arguments only, values pass through `Shape` format gates, and exceptions only emit their class name (never `getMessage()`, which can carry local paths and Windows usernames).
2. **Never blocks boot.** `TroubleReport.consider` wraps everything in a single `try/catch (Throwable)` so a report write failure never stops the game from starting.

## Components

- `Shape`: allowlist format gate (generation name, 12-char sha, release tag, fixed vocabulary word, dotted class name). Unrecognized values become `unknown`.
- `Trigger`: the six report-worthy states in priority order (enum declaration order is priority).
- `Triggers`: reads updater state and emits at most one prioritized trigger with a dedup fingerprint.
- `BoxFacts`: typed snapshot of ledger counts, system properties, core/engine slots, and sync status.
- `ReportBody`: builds the report text from typed inputs, putting the player action at the top.
- `ReportState`: stores remembered fingerprints and `@streak`/`@quiet` counters in `.bamfos-updater/report-state`.
- `ReportSlot`: picks the output path in `crash-reports/` (creating the directory if needed) and prunes old reports.
- `TroubleReport`: public entry point called at the end of client/server syncs and queried by the BIOS status row.

## Guardrails

- Never widen a `Shape` pattern to pass unrecognized text; `unknown` is always safer than leaking a path or token.
- Class-name gates require a dot so bare identifiers (like tokens) cannot pass.
- `ReportSourceGateTest` verifies only `ReportState` and `Triggers` read files in this package, and `ReportBody` never accepts raw `String`/`Path`/`File` arguments.
- Core notes are deleted only after the report lands on disk.
- Factory reset preserves `crash-reports/` as a protected folder.
