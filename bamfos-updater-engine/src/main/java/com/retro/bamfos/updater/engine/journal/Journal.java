package com.retro.bamfos.updater.engine.journal;

import com.retro.bamfos.updater.engine.json.JsonWriter;
import com.retro.bamfos.updater.engine.json.MiniJson;
import com.retro.bamfos.updater.engine.util.HashUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Crash-safe apply journal written after all downloads verify and before any file moves, so an interrupted apply finishes offline on next boot.
public final class Journal {

    /** move = staged file (from) into place (to), sha-verified; delete = remove a superseded path. */
    public record Op(String kind, String from, String to, String sha256) {
    }

    public String targetVersion = "";
    public final List<Op> ops = new ArrayList<>();

    public static Path pathIn(Path instanceDir) {
        return instanceDir.resolve(".bamfos-updater").resolve("journal.json");
    }

    public static Journal load(Path file) throws IOException {
        Journal j = new Journal();
        if (!Files.isRegularFile(file)) {
            return j;
        }
        Object root = MiniJson.parse(Files.readString(file, StandardCharsets.UTF_8));
        if (root instanceof Map<?, ?> m) {
            j.targetVersion = m.get("target_version") instanceof String s ? s : "";
            if (m.get("ops") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> om) {
                        j.ops.add(new Op(str(om.get("kind")), str(om.get("from")), str(om.get("to")), str(om.get("sha256"))));
                    }
                }
            }
        }
        return j;
    }

    private static String str(Object v) {
        return v instanceof String s ? s : "";
    }

    public void save(Path file) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("target_version", targetVersion);
        List<Object> list = new ArrayList<>();
        for (Op op : ops) {
            Map<String, Object> om = new LinkedHashMap<>();
            om.put("kind", op.kind());
            om.put("from", op.from());
            om.put("to", op.to());
            om.put("sha256", op.sha256());
            list.add(om);
        }
        root.put("ops", list);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, JsonWriter.write(root), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Executes every op idempotently against instanceDir; a half-replayed journal replays clean.
     * moves verify the staged file's sha before placing (a stale helper/journal with wrong bytes
     * no-ops rather than corrupting, .3); deletes are unconditional (the path is superseded).
     */
    public void replay(Path instanceDir) throws IOException {
        for (Op op : ops) {
            Path to = instanceDir.resolve(op.to());
            if (op.kind().equals("delete")) {
                Files.deleteIfExists(to);
            } else {
                Path from = instanceDir.resolve(op.from());
                if (!Files.isRegularFile(from)) {
                    continue; // already moved by a previous partial replay
                }
                if (!HashUtils.sha256(from).equals(op.sha256())) {
                    continue; // stale staging (rollback race) -- never place unverified bytes
                }
                Files.createDirectories(to.getParent());
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
