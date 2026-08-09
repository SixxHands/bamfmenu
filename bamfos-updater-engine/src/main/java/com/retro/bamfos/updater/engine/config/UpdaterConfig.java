package com.retro.bamfos.updater.engine.config;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

// Per-machine settings in bamfos-updater.properties (never managed or committed). Missing file just uses defaults.
public record UpdaterConfig(
        String githubToken,
        String repo,
        String serverHost,
        int serverPort,
        List<String> pins,
        String tagPin,
        boolean autoRestart) {

    /** Default release repo. Kept in one place so trouble reports stay accurate when checking overrides. */
    public static final String DEFAULT_REPO = "SixxHands/bamfos-pack";

    /** Redacts the token so accidental logging of UpdaterConfig never leaks the shared PAT
     *  into logs/latest.log. */
    @Override
    public String toString() {
        return "UpdaterConfig[githubToken=" + (githubToken == null || githubToken.isEmpty() ? "<none>" : "<set>")
                + ", repo=" + repo + ", serverHost=" + serverHost + ", serverPort=" + serverPort
                + ", pins=" + pins + ", tagPin=" + tagPin + ", autoRestart=" + autoRestart + "]";
    }

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
                token(props, instanceDir),
                props.getProperty("repo", DEFAULT_REPO),
                props.getProperty("server_host", ""),
                port(props.getProperty("server_port", "25565")),
                List.copyOf(pins),
                props.getProperty("tag", ""),
                !"false".equalsIgnoreCase(props.getProperty("auto_restart", "true")));
    }

    // Properties file token overrides the shim's embedded token so operators can swap a dead token without waiting for a new shim jar.
    private static String token(Properties props, Path instanceDir) {
        String fromFile = props.getProperty("github_token", "").trim();
        return fromFile.isEmpty() ? EmbeddedToken.read(instanceDir) : fromFile;
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
        // A BOM'd save (PowerShell 5.1 -Encoding utf8, Notepad's "UTF-8 with BOM") glues U+FEFF onto the first key, so a github_token pasted as line 1 reads as absent while it sits right there in the file; trim() does not touch FEFF, it is not whitespace to Java. Spelled in hex because a FEFF string literal is invisible in source.
        if (!text.isEmpty() && text.charAt(0) == 0xFEFF) {
            text = text.substring(1);
        } else if (text.length() >= 3 && text.charAt(0) == 0xEF && text.charAt(1) == 0xBB && text.charAt(2) == 0xBF) {
            text = text.substring(3); // strip UTF-8 BOM (EF BB BF) if it was decoded as three latin-1 chars
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
