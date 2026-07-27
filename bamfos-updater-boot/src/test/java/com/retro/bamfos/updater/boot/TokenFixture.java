package com.retro.bamfos.updater.boot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Shared helper for token leak tests: locates the repo and the embedded build token. */
final class TokenFixture {

    /** Walks up from the test's working dir (the module dir) to whatever keeps settings.gradle. */
    static Path repoRoot() {
        Path start = Paths.get("").toAbsolutePath();
        for (Path p = start; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("settings.gradle"))) {
                return p;
            }
        }
        throw new IllegalStateException("no settings.gradle at or above " + start);
    }

    /** Reads the same token inputs in the same order as embedToken; empty string is a valid tokenless build. */
    static String buildInputToken() throws IOException {
        String env = System.getenv("BAMFOS_GITHUB_TOKEN");
        if (env != null) {
            return env.trim();
        }
        Path local = repoRoot().resolve("bamfos-token.local");
        return Files.isRegularFile(local) ? Files.readString(local).trim() : "";
    }

    private TokenFixture() {
    }
}
