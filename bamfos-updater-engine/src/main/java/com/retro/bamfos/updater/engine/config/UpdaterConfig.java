package com.retro.bamfos.updater.engine.config;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * bamfos-updater.properties: per-machine settings + the token. Never managed, never
 * committed; ships in seed zips. Absent file = all defaults (the updater still boots the game).
 */
public record UpdaterConfig(
        String githubToken,
        String repo,
        String serverHost,
        int serverPort,
        List<String> pins,
        String tagPin,
        boolean autoRestart) {

    /** Still declares IOException even though it doesn't throw anymore, because UpdaterOps catches it
     *  around every load call. */
    public static UpdaterConfig load(Path instanceDir) throws IOException {
        Properties props = rawProps(instanceDir);
        List<String> pins = new ArrayList<>();
        String raw = props.getProperty("pin", "");
        for (String s : raw.split(",")) {
            if (!s.isBlank()) {
                pins.add(s.trim());
            }
        }
        return new UpdaterConfig(
                props.getProperty("github_token", ""),
                props.getProperty("repo", "SixxHands/bamfos-pack"),
                props.getProperty("server_host", ""),
                port(props.getProperty("server_port", "25565")),
                List.copyOf(pins),
                props.getProperty("tag", ""),
                !"false".equalsIgnoreCase(props.getProperty("auto_restart", "true")));
    }

    /** Parses every valid line even if the file has bad encoding or malformed unicode escapes.
     *  Properties.load fails on pasted Windows paths and readString fails on ANSI files; dropping the
     *  whole file would lose the token and cause silent 404s on private repo polls. */
    public static Properties rawProps(Path instanceDir) {
        Path p = instanceDir.resolve(".bamfos-updater").resolve("bamfos-updater.properties");
        Properties props = new Properties();
        if (!Files.isRegularFile(p)) {
            return props;
        }
        String text;
        try {
            text = Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException notUtf8) {
            try {
                // fallback to latin-1 so ANSI-encoded files still load instead of failing
                text = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
            } catch (IOException | RuntimeException gone) {
                return props;
            }
        }
        try {
            props.load(new StringReader(text));
            return props;
        } catch (IOException | RuntimeException malformed) {
            props.clear(); // load can populate before it throws, and the split below re-reads everything
        }
        for (String line : text.split("\\R")) {
            String s = line.trim();
            int eq = s.indexOf('=');
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("!") || eq <= 0) {
                continue;
            }
            props.setProperty(s.substring(0, eq).trim(), s.substring(eq + 1).trim());
        }
        return props;
    }

    /** Guard bad hand-edited server_port values so they don't crash server boot or fail on the socket. */
    private static int port(String raw) {
        try {
            int p = Integer.parseInt(raw.trim());
            return p >= 1 && p <= 65535 ? p : 25565;
        } catch (NumberFormatException bad) {
            return 25565;
        }
    }
}
