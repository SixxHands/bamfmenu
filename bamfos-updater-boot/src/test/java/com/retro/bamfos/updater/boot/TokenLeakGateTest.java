package com.retro.bamfos.updater.boot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fails the suite if any committable file (tracked or untracked, excluding gitignored files like
 * bamfos-token.local) contains a token-shaped string. When git is not on PATH, falls back to a
 * directory walk using SKIP_DIRS, so keep SKIP_DIRS in sync with gitignored secret locations.
 */
class TokenLeakGateTest {

    /** GitHub token prefixes. Don't match bare 40-char hex since the repo has lots of SHA-1/SHA-256 hashes. */
    private static final Pattern TOKEN_SHAPED = Pattern.compile(
            "gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{40,}");

    private static final Set<String> BINARY = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".ico", ".jar", ".zip", ".wav", ".ogg", ".ttf",
            ".dat", ".wad", ".flf", ".class", ".bin");

    /** Mirrors .gitignore for the no-git fallback, mainly skipping run/ and build outputs. */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".gradle", "build", "bin", "out", ".idea", "run", "scratch", "reference",
            "docs", "bamfmenu-backups", "BAMFOS-backup");

    @Test
    void noCommittableFileCarriesATokenShapedString() throws IOException {
        Path root = TokenFixture.repoRoot();
        List<Path> files = committableFiles(root);
        List<String> hits = new ArrayList<>();
        int scanned = 0;
        for (Path p : files) {
            String name = p.getFileName().toString().toLowerCase();
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && BINARY.contains(name.substring(dot))) {
                continue;
            }
            if (!Files.isRegularFile(p)) {
                continue; // ls-files lists a path git knows about, not necessarily one on disk right now
            }
            scanned++;
            // Use Latin-1 so any byte sequence decodes without throwing on binary or odd encodings.
            String text = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
            if (TOKEN_SHAPED.matcher(text).find()) {
                hits.add(root.relativize(p).toString());
            }
        }
        // Fail if nothing was scanned so a broken path lookup doesn't look like a clean pass.
        assertTrue(scanned > 100, String.valueOf(scanned));
        assertEquals(List.of(), hits);
    }

    private static List<Path> committableFiles(Path root) throws IOException {
        List<Path> fromGit = gitLsFiles(root);
        return fromGit.isEmpty() ? walk(root) : fromGit;
    }

    private static List<Path> gitLsFiles(Path root) {
        List<Path> out = new ArrayList<>();
        try {
            // tracked plus untracked-not-ignored, covering everything git add -A would stage
            Process git = new ProcessBuilder("git", "ls-files", "-z", "--cached", "--others", "--exclude-standard")
                    .directory(root.toFile())
                    .redirectErrorStream(false)
                    .start();
            byte[] raw;
            try (InputStream in = git.getInputStream()) {
                raw = in.readAllBytes();
            }
            if (git.waitFor() != 0) {
                return List.of();
            }
            for (String rel : new String(raw, StandardCharsets.UTF_8).split("\0")) {
                if (!rel.isEmpty()) {
                    out.add(root.resolve(rel));
                }
            }
        } catch (IOException | InterruptedException noGit) {
            return List.of();
        }
        return out;
    }

    private static List<Path> walk(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // Prune ignored dirs up front so we don't walk .git.
                return SKIP_DIRS.contains(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!file.getFileName().toString().equals("bamfos-token.local")) {
                    out.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException locked) {
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }
}
