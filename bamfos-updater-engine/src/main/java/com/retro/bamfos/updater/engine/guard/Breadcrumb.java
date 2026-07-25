package com.retro.bamfos.updater.engine.guard;

import com.retro.bamfos.updater.engine.json.MiniJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

// Reads and consumes last-disconnect.json written by bamfmenu on a mismatch kick (deletes malformed files so they don't loop forever).
public record Breadcrumb(String server, String reason, String fingerprint, long at) {

    public static Path pathIn(Path instanceDir) {
        return instanceDir.resolve(".bamfos-updater").resolve("last-disconnect.json");
    }

    public static Optional<Breadcrumb> consume(Path instanceDir) {
        Path p = pathIn(instanceDir);
        if (!Files.isRegularFile(p)) {
            return Optional.empty();
        }
        Optional<Breadcrumb> crumb = Optional.empty();
        try {
            Object root = MiniJson.parse(Files.readString(p, StandardCharsets.UTF_8));
            if (root instanceof Map<?, ?> m
                    && m.get("reason") instanceof String reason && !reason.isEmpty()
                    && m.get("fingerprint") instanceof String fp && !fp.isEmpty()) {
                String server = m.get("server") instanceof String s ? s : "";
                long at = m.get("at") instanceof Long l ? l : 0L;
                crumb = Optional.of(new Breadcrumb(server, reason, fp, at));
            }
        } catch (IOException | RuntimeException unreadable) {
            // Fall through and delete the corrupt file so we don't loop on it.
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
        return crumb;
    }
}
