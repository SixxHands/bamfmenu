package com.retro.bamfos.updater.engine.report;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source and reflection gate enforcing that report code cannot read arbitrary files or logs from disk,
 * catching regressions that behavioral tests alone would miss.
 */
class ReportSourceGateTest {

    /**
     * Only files in this package allowed to read disk: ReportState (ints and 12-char hex fingerprints) and
     * Triggers (rejection files and core notes, all passed through Shape gates).
     */
    private static final List<String> OWN_THE_DISK = List.of("ReportState", "Triggers");

    /** File-reading APIs used in this codebase. Files.write and Files.list are excluded because writes only
     *  output our own data, and directory listings return Path objects that get filtered before printing. */
    private static final List<String> READS = List.of(
            "Files.read", "Files.lines", "newBufferedReader", "newInputStream", "readAllLines",
            "Scanner", "FileReader", "JarFile");

    /** Types that can take player text, including Object since untyped parameters can receive a
     *  String. */
    private static final List<Class<?>> TEXTY =
            List.of(String.class, CharSequence.class, Object.class, Path.class, java.io.File.class);

    /** Verifies no report class reads from disk except ReportState and Triggers. */
    @Test
    void nothingButTheTwoBookkeepersEverReadsAFile() throws IOException {
        List<String> offenders = new ArrayList<>();
        List<String> scanned = sources();
        for (String name : scanned) {
            if (!OWN_THE_DISK.contains(name) && reads(ReportFixture.codeOf(name)) > 0) {
                offenders.add(name);
            }
        }
        // make sure the scanner still catches a planted read call and both files that actually read from disk
        assertTrue(reads("String s = Files.readString(p);") > 0);
        assertTrue(scanned.size() >= 7, String.valueOf(scanned.size()));
        for (String bookkeeper : OWN_THE_DISK) {
            assertTrue(reads(ReportFixture.codeOf(bookkeeper)) > 0,
                    bookkeeper + " no longer reads a file, so it is either gone or this list is stale, and "
                            + "either way the scanner has stopped proving anything");
        }
        assertEquals(List.of(), offenders);
    }

    /** The writer entry point doesn't accept raw strings, so callers can't bypass formatting checks. Private
     *  helpers only pass internal literals and aren't checked here. */
    @Test
    void theWriterCannotBeHandedTextAtAll() {
        int params = 0;
        for (Method m : ReportBody.class.getDeclaredMethods()) {
            if (Modifier.isPrivate(m.getModifiers())) {
                continue;
            }
            for (Class<?> p : m.getParameterTypes()) {
                params++;
                assertTrue(!TEXTY.contains(p), String.valueOf(m.getName()) + " / " + String.valueOf(p.getSimpleName()));
            }
        }
        assertTrue(params >= 4, String.valueOf(params));
    }

    /** And typed all the way down, because a record with a String in it is the same hole one indirection further
     *  away: the writer would be handed the free text inside a wrapper. */
    @Test
    void everyArgumentIsTypedAllTheWayDown() {
        int checked = 0;
        for (Class<?> record : List.of(Triggers.Fault.class, Triggers.Line.class, Triggers.Note.class,
                BoxFacts.Box.class, BoxFacts.CoreJar.class, BoxFacts.EngineSlot.class)) {
            for (RecordComponent c : record.getRecordComponents()) {
                checked++;
                String type = c.getGenericType().getTypeName();
                assertTrue(!type.contains("String") && !type.contains("CharSequence")
                                && !type.contains("Path") && !type.contains("Object"),
                        record.getSimpleName() + "." + c.getName() + " is a " + type
                                + ", so free text can ride into the writer inside it");
            }
        }
        assertTrue(checked >= 25, String.valueOf(checked));
    }

    /** A Shape guarantees a value matched our expected format, so its constructor must stay inaccessible. */
    @Test
    void aShapeCanOnlyComeOutOfAGate() {
        Constructor<?>[] all = Shape.class.getDeclaredConstructors();
        assertTrue(all.length >= 1);
        for (Constructor<?> c : all) {
            assertTrue(Modifier.isPrivate(c.getModifiers()));
        }
    }

    /** The simple names of every java file in the report package. */
    private static List<String> sources() throws IOException {
        Path pkg = ReportFixture.repoRoot().resolve("bamfos-updater-engine/src/main/java/com/retro/bamfos/"
                + "updater/engine/report");
        try (Stream<Path> listing = Files.list(pkg)) {
            return listing.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".java"))
                    .map(n -> n.substring(0, n.length() - ".java".length()))
                    .sorted().toList();
        }
    }

    private static int reads(String code) {
        int hits = 0;
        for (String api : READS) {
            int at = code.indexOf(api);
            while (at >= 0) {
                hits++;
                at = code.indexOf(api, at + 1);
            }
        }
        return hits;
    }
}
