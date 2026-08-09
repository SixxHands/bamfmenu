package com.retro.bamfos.updater.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;

/* Fallback crash-reports/ note for when the core loads but can't load an engine (like an unwritable
 * .bamfos-updater/ folder). Only emits strict grammar tokens and exception class names so it can never
 * leak paths or tokens. */
final class CoreNote {

    /**
     * Strict allowlist grammar (digits, core-NNNN.jar, fixed tokens, or dotted Java class name).
     * Emitting nothing outside this set keeps the note credential-free without needing a redactor.
     */
    private static final Pattern GRAMMAR = Pattern.compile(
            "\\d+|core-\\d{4}\\.jar|none|unknown|client|server"
                    + "|[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    /** Byte-identical punctuation to vanilla CrashReport's, so a player sorting the folder by name gets ours
     *  interleaved with real crashes instead of clumped at one end. */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss").withZone(ZoneId.systemDefault());

    /**
     * Best-effort failure note. Logs only cause's class name, never its message,
     * which can contain URLs or local paths with the player's Windows username.
     */
    static void write(Path instanceDir, String[] args, String runningCore, Throwable cause) {
        try {
            long now = System.currentTimeMillis();
            Throwable under = cause == null ? null : cause.getCause();
            List<String> lines = List.of(
                    "BAMFOS UPDATER PROBLEM REPORT",
                    "",
                    "The updater could not load its engine, so your pack was not checked for updates. "
                            + "The game booted anyway.",
                    "",
                    "Send this file to the pack owner.",
                    "There is no password and no login token in this file.",
                    "",
                    "note-format 1",
                    "at " + now,
                    "side " + side(args),
                    "java " + Runtime.version().feature(),
                    "pid " + ProcessHandle.current().pid(),
                    "core " + clean(runningCore, "none"),
                    "cause " + clean(cause == null ? null : cause.getClass().getName(), "unknown"),
                    "cause-2 " + clean(under == null ? null : under.getClass().getName(), "none"),
                    "",
                    "This is the short version. The updater writes a longer one on the next boot if it "
                            + "manages to load.");
            // Use the -note suffix so the engine can find and replace it, while keeping the bamfos-updater-
            // prefix and .txt extension so log pruning still cleans it up.
            String name = "bamfos-updater-" + STAMP.format(Instant.ofEpochMilli(now)) + "-note.txt";
            if (!put(instanceDir.resolve("crash-reports"), name, lines)) {
                put(instanceDir.resolve(".bamfos-updater").resolve("reports"), name, lines);
            }
        } catch (Throwable ignored) {
            // never throw here: this runs inside CoreEntry's catch block and must not mask the engine failure
        }
    }

    /* Duplicated from ReportSlot because the core can't link the engine and only runs this
       when no engine loaded. Keep both in sync. */

    /** Returns true if the note was written. Keep createDirectories and the write in the same try block
     *  so a read-only crash-reports folder still falls through to the fallback location. */
    private static boolean put(Path dir, String name, List<String> lines) {
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(name), lines, StandardCharsets.UTF_8);
            System.out.println("[updater] the updater could not load its engine, what happened is written "
                    + "down in " + dir.getFileName() + "/" + name);
            return true;
        } catch (Throwable unwritable) {
            return false;
        }
    }

    /** Checks raw argv before full arg parsing since the stub passes its flags through. Returns "unknown"
     *  when neither flag is present (e.g. a manual jar run) to stay within the allowed token set. */
    private static String side(String[] args) {
        for (String a : args) {
            if ("--client".equals(a)) {
                return "client";
            }
            if ("--server".equals(a)) {
                return "server";
            }
        }
        return "unknown";
    }

    private static String clean(String token, String fallback) {
        return token != null && GRAMMAR.matcher(token).matches() ? token : fallback;
    }

    private CoreNote() {
    }
}
