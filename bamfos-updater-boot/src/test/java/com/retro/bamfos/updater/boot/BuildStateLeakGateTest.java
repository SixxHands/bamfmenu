package com.retro.bamfos.updater.boot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Checks Gradle's .gradle/ build cache so raw tokens never leak into executionHistory.bin (which git ignores).
class BuildStateLeakGateTest {

    /** Same token patterns as TokenLeakGateTest so both gates check the exact same formats. */
    private static final Pattern TOKEN_SHAPED = Pattern.compile(
            "gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{40,}");

    @Test
    void noGradleBuildStateCarriesATokenShapedString() throws IOException {
        Path state = TokenFixture.repoRoot().resolve(".gradle");
        if (!Files.isDirectory(state)) {
            return; // Nothing to scan on a fresh clone with no build state yet.
        }
        List<String> hits = new ArrayList<>();
        Files.walkFileTree(state, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    // latin-1 decodes arbitrary bytes without throwing so binary files like executionHistory.bin still get scanned
                    String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                    if (TOKEN_SHAPED.matcher(text).find()) {
                        hits.add(state.relativize(file).toString());
                    }
                } catch (IOException | OutOfMemoryError unreadable) {
                    // a lock file the daemon keeps, or something too big to hold in one string. Skipping it
                    // is right: this gate is about what the build WROTE, not about reading every byte
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException locked) {
                return FileVisitResult.CONTINUE;
            }
        });
        assertEquals(List.of(), hits);
    }
}
