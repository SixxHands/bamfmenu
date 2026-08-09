package com.retro.bamfos.updater.engine.report;

import com.retro.bamfos.updater.engine.util.HashUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Shared report test fixtures and fake tokens. Never use real tokens; fakes are assembled from split literals
 * at runtime so TokenLeakGateTest won't flag this file, and comparisons use sha256.
 */
final class ReportFixture {

    /** Token-shaped test value with 40 chars after the prefix so it passes both the 20 and 36 char minimums. */
    static String fakeToken() {
        return "gh" + "p_" + "F4KEfake".repeat(5);
    }

    /** Low-entropy, shapeless token invisible to L3 and L5, testing that L2 deny-value matching catches it. */
    static String fakeOpaque() {
        return "hunter2" + "-hunter2" + "-hunter2";
    }

    static Path own(Path dir) {
        return dir.resolve(".bamfos-updater");
    }

    static void props(Path dir, String body) throws IOException {
        write(own(dir).resolve("bamfos-updater.properties"), body);
    }

    static void coreJar(Path dir, String name) throws IOException {
        write(own(dir).resolve("core").resolve(name), "not really a jar");
    }

    static void coreRejected(Path dir, String gen, long at) throws IOException {
        append(own(dir).resolve("core").resolve("core-rejected"), gen + "\t" + at + "\n");
    }

    static void engineRejected(Path dir, String sha, long at) throws IOException {
        append(own(dir).resolve("engine").resolve("engine-rejected"), sha + "\t" + at + "\n");
    }

    static void lastSync(Path dir, String result) throws IOException {
        write(own(dir).resolve("last-sync.json"), "{\"at\":1,\"tag\":\"v1\",\"result\":\"" + result
                + "\",\"summary\":\"a fixture\",\"held\":false}");
    }

    /** Ledger with a quarantined row whose free-text reason can carry a user's jar filename from disk. */
    static void ledgerWithQuarantine(Path dir, String path, String reason) throws IOException {
        write(own(dir).resolve("ledger.json"), "{\"ledger_version\":1,\"pack_id\":\"bamfos\","
                + "\"installed_version\":\"v0.0.1-test\",\"files\":{\"" + path
                + "\":{\"sha256\":\"deadbeef\",\"quarantined\":true,\"quarantine_reason\":\"" + reason
                + "\"}}}");
    }

    static void latestLog(Path dir, String... lines) throws IOException {
        write(dir.resolve("logs").resolve("latest.log"), String.join("\n", lines) + "\n");
    }

    /** All of our report files across both slots, newest first. */
    static List<Path> reports(Path dir) {
        List<Path> out = new ArrayList<>();
        for (Path slot : List.of(dir.resolve("crash-reports"), own(dir).resolve("reports"))) {
            if (!Files.isDirectory(slot)) {
                continue;
            }
            try (Stream<Path> listing = Files.list(slot)) {
                listing.filter(p -> p.getFileName().toString().startsWith("bamfos-updater-")
                        && p.getFileName().toString().endsWith(".txt")).forEach(out::add);
            } catch (IOException gone) {
                throw new IllegalStateException(gone);
            }
        }
        out.sort((a, b) -> Long.compare(b.toFile().lastModified(), a.toFile().lastModified()));
        return out;
    }

    static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /**
     * Returns true if any 16-char window of the secret appears in haystack (catching partial leaks like
     * EmbeddedTokenJarTest), compared as sha256 so raw values never appear in assertion failures.
     */
    static boolean carriesAWindowOf(String haystack, String secret) {
        int width = Math.min(16, secret.length());
        Set<String> windows = new HashSet<>();
        for (int at = 0; at + width <= haystack.length(); at++) {
            windows.add(sha(haystack.substring(at, at + width)));
        }
        for (int at = 0; at + width <= secret.length(); at++) {
            if (windows.contains(sha(secret.substring(at, at + width)))) {
                return true;
            }
        }
        return false;
    }

    /** Reads source text for a class in this package so tests can check static wiring. Two of the three token
     *  paths can't be triggered in unit tests (one needs the build encoder, one needs an env var), so we
     *  check the source directly. */
    static String sourceOf(String simpleName) throws IOException {
        Path p = repoRoot().resolve("bamfos-updater-engine/src/main/java/com/retro/bamfos/updater/engine/"
                + "report/" + simpleName + ".java");
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** Strips comments before source-gate checks so comments mentioning forbidden calls don't cause false hits. */
    static String codeOf(String simpleName) throws IOException {
        String src = sourceOf(simpleName);
        StringBuilder out = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (inString) {
                out.append(c);
                if (c == '\\') {
                    out.append(src.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
            } else if (src.startsWith("//", i)) {
                while (i < src.length() && src.charAt(i) != '\n') {
                    i++;
                }
                out.append('\n');
            } else if (src.startsWith("/*", i)) {
                i += 2;
                while (i + 1 < src.length() && !src.startsWith("*/", i)) {
                    i++;
                }
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    static Path repoRoot() {
        Path start = Paths.get("").toAbsolutePath();
        for (Path p = start; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("settings.gradle"))) {
                return p;
            }
        }
        throw new IllegalStateException("no settings.gradle at or above " + start);
    }

    private static String sha(String s) {
        return HashUtils.sha256(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void write(Path p, String body) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, body, StandardCharsets.UTF_8);
    }

    private static void append(Path p, String body) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, Files.isRegularFile(p) ? read(p) + body : body, StandardCharsets.UTF_8);
    }

    private ReportFixture() {
    }
}
