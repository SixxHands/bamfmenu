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

    public static UpdaterConfig load(Path instanceDir) throws IOException {
        Path p = instanceDir.resolve(".bamfos-updater").resolve("bamfos-updater.properties");
        Properties props = new Properties();
        if (Files.isRegularFile(p)) {
            props.load(new StringReader(Files.readString(p, StandardCharsets.UTF_8)));
        }
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
                Integer.parseInt(props.getProperty("server_port", "25565")),
                List.copyOf(pins),
                props.getProperty("tag", ""),
                !"false".equalsIgnoreCase(props.getProperty("auto_restart", "true")));
    }
}
