package com.retro.bamfos.updater.engine.guard;

import com.retro.bamfos.updater.engine.json.JsonWriter;
import com.retro.bamfos.updater.engine.json.MiniJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

// Tracks what the last kick-recovery boot actually changed per kick fingerprint so bamfmenu won't tell you to restart in an infinite loop.
public final class DisconnectHistory {

    public static final class Entry {
        public long count;
        public long lastAt;
        public String lastAction = "none"; // "none" or "quarantined <paths>"
        public boolean versionChanged;
    }

    public final Map<String, Entry> entries = new LinkedHashMap<>();

    public static Path pathIn(Path instanceDir) {
        return instanceDir.resolve(".bamfos-updater").resolve("disconnect-history.json");
    }

    public static DisconnectHistory load(Path instanceDir) {
        DisconnectHistory h = new DisconnectHistory();
        Path p = pathIn(instanceDir);
        if (!Files.isRegularFile(p)) {
            return h;
        }
        try {
            Object root = MiniJson.parse(Files.readString(p, StandardCharsets.UTF_8));
            if (root instanceof Map<?, ?> m && m.get("entries") instanceof Map<?, ?> em) {
                for (Map.Entry<?, ?> e : em.entrySet()) {
                    if (e.getValue() instanceof Map<?, ?> v) {
                        Entry en = new Entry();
                        en.count = v.get("count") instanceof Long l ? l : 0L;
                        en.lastAt = v.get("last_at") instanceof Long l ? l : 0L;
                        en.lastAction = v.get("last_action") instanceof String s ? s : "none";
                        en.versionChanged = Boolean.TRUE.equals(v.get("version_changed"));
                        h.entries.put(String.valueOf(e.getKey()), en);
                    }
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            // corrupt history = start fresh; worst case one extra repairable screen
        }
        return h;
    }

    public void record(String fingerprint, String action, boolean versionChanged, long at) {
        Entry en = entries.computeIfAbsent(fingerprint, k -> new Entry());
        en.count++;
        en.lastAt = at;
        en.lastAction = action;
        en.versionChanged = versionChanged;
    }

    public void save(Path instanceDir) throws IOException {
        Map<String, Object> em = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("count", e.getValue().count);
            v.put("last_at", e.getValue().lastAt);
            v.put("last_action", e.getValue().lastAction);
            if (e.getValue().versionChanged) {
                v.put("version_changed", true);
            }
            em.put(e.getKey(), v);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("entries", em);
        Path p = pathIn(instanceDir);
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.writeString(tmp, JsonWriter.write(root), StandardCharsets.UTF_8);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
    }
}
