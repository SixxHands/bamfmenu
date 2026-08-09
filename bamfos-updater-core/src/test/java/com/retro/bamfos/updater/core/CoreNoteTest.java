package com.retro.bamfos.updater.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests CoreNote (written when no engine can load) and verifies its allowlist grammar keeps
 * untrusted throwable messages out of the note without needing a redactor.
 */
class CoreNoteTest {

    /**
     * Same as CoreEntryTest: the rethrow test leaves an engine classloader open, and Windows won't
     * delete a jar that's still locked. Clean up whatever the OS allows.
     */
    @TempDir(cleanup = CleanupMode.NEVER)
    Path tmp;

    private Path crashReports() {
        return tmp.resolve("crash-reports");
    }

    private String onlyNote() throws IOException {
        List<Path> notes = notes(crashReports());
        assertEquals(1, notes.size());
        return Files.readString(notes.get(0), StandardCharsets.UTF_8);
    }

    private static List<Path> notes(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> listing = Files.list(dir)) {
            return listing.filter(p -> p.getFileName().toString().startsWith("bamfos-updater-")
                    && p.getFileName().toString().endsWith("-note.txt"))
                    .sorted(Comparator.comparing(Path::toString)).toList();
        }
    }

    /** Checks the full note format and the -note filename suffix that the engine scans for. */
    @Test
    void theNoteCarriesTheAskFirstAndThenTheFields() throws IOException {
        CoreNote.write(tmp, new String[]{"--client", "--dir", tmp.toString()}, "core-0004.jar",
                new IllegalStateException("boom", new java.io.IOException("under it")));

        String body = onlyNote();
        assertTrue(body.startsWith("BAMFOS UPDATER PROBLEM REPORT"), body);
        assertTrue(body.contains("Send this file to the pack owner."));
        assertTrue(body.contains("There is no password and no login token in this file."));
        assertTrue(body.contains("note-format 1"));
        assertTrue(body.contains("side client"));
        assertTrue(body.contains("core core-0004.jar"));
        assertTrue(body.contains("cause java.lang.IllegalStateException"));
        assertTrue(body.contains("cause-2 java.io.IOException"));
        assertTrue(body.contains("java " + Runtime.version().feature()));
        assertTrue(notes(crashReports()).get(0).getFileName().toString()
                .matches("bamfos-updater-\\d{4}-\\d{2}-\\d{2}_\\d{2}\\.\\d{2}\\.\\d{2}-note\\.txt"));
    }

    /**
     * Never put exception messages in the note (they can leak URLs or Windows usernames);
     * only write the validated class name.
     */
    @Test
    void aThrowableMessageFullOfSecretsContributesNothingButItsClassName() throws IOException {
        String nasty = "GET https://x-access-token:" + fake() + "@github.com/o/r failed for "
                + "C:\\Users\\somebody\\AppData\\Roaming\\thing";

        CoreNote.write(tmp, new String[]{"--server"}, "core-0004.jar", new java.io.IOException(nasty));

        String body = onlyNote();
        assertTrue(body.contains("cause java.io.IOException"));
        assertFalse(body.contains("somebody"));
        assertFalse(body.contains("github.com"));
        assertFalse(body.contains(fake().substring(0, 16)));
        assertTrue(body.contains("side server"));
    }

    /** Values outside the allowlist should be written as "unknown". */
    @Test
    void anythingOffTheGrammarIsEmittedAsUnknownOrNone() throws IOException {
        CoreNote.write(tmp, new String[]{"--client"}, "../../mods/evil.jar", null);

        String body = onlyNote();
        assertTrue(body.contains("core none"));
        assertTrue(body.contains("cause unknown"));
        assertTrue(body.contains("cause-2 none"));
    }

    /** Make crash-reports a file to mimic a read-only folder or antivirus lock; the note should still write. */
    @Test
    void anUnwritableCrashReportsFallsThroughToOurOwnFolder() throws IOException {
        Files.writeString(crashReports(), "somebody put a file where the folder goes");

        CoreNote.write(tmp, new String[]{"--client"}, "core-0004.jar", new java.io.IOException("x"));

        assertEquals(1, notes(tmp.resolve(".bamfos-updater").resolve("reports")).size());
    }

    /**
     * Verifies CoreEntry rethrows after writing the note so the crash marker stays set.
     * Uses a non-empty directory at the engine path to make ensureAndLoad fail.
     */
    @Test
    void aCoreThatCannotGetAnEngineWritesTheNoteAndStillLetsTheFaultOut() throws IOException {
        Path slot = tmp.resolve(".bamfos-updater").resolve("engine").resolve("bamfos-updater-engine.jar");
        Files.createDirectories(slot);
        Files.writeString(slot.resolve("busy.txt"), "so the move cannot replace this");

        assertThrows(Exception.class,
                () -> CoreEntry.enter(new String[]{"--client", "--dir", tmp.toString()}),
                "core entry must throw on failure so fallback selection can advance to an earlier generation");

        assertEquals(1, notes(crashReports()).size());
    }

    /** Build the token at runtime from split strings so TokenLeakGateTest doesn't flag this test file. */
    private static String fake() {
        return "gh" + "p_" + "F4KEfake".repeat(5);
    }
}
