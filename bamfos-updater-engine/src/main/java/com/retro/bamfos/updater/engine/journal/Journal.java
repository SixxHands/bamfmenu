package com.retro.bamfos.updater.engine.journal;

import com.retro.bamfos.updater.engine.json.JsonWriter;
import com.retro.bamfos.updater.engine.json.MiniJson;
import com.retro.bamfos.updater.engine.util.HashUtils;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Crash-safe apply journal written after all downloads verify and before any file moves, so an interrupted apply finishes offline on next boot.
public final class Journal {

    /**
     * move replaces an existing target; delete removes a superseded path; seed and rename refuse an
     * occupied destination. Plans can sit until the next boot, so refusing kinds stop us from overwriting
     * a config, servers.dat, or .disabled jar the player created in the meantime.
     */
    public record Op(String kind, String from, String to, String sha256) {
    }

    /**
     * Replay outcome split into retryable (locked target, staged bytes stay local for next boot) and
     * abandoned (missing/changed source or player-owned destination). Both buckets gate the ledger so we
     * never record a target sha for bytes that didn't land.
     */
    public record Result(List<Op> retryable, List<Op> abandoned) {
        public boolean allLanded() {
            return retryable.isEmpty() && abandoned.isEmpty();
        }
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
        Object root;
        try {
            root = MiniJson.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (MiniJson.JsonError | CharacterCodingException corrupt) {
            // A crash can leave a truncated journal.json; letting the parse throw bricks step 0 on every boot.
            // Delete it and treat ops as abandoned: the ledger gate kept unlanded paths out, so next sync re-derives them.
            System.out.println("[updater] journal is unreadable (" + corrupt
                    + "), discarding it, those paths get re-planned");
            Files.deleteIfExists(file);
            return j;
        }
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
     * Replays journal ops idempotently. move/rename verify the source sha first (and rename/seed refuse
     * occupied targets); delete is unconditional. Every op that didn't land (errors and skipped sources)
     * is returned rather than thrown so one stuck file doesn't poison the replay or commit bad ledger rows.
     */
    public Result replay(Path instanceDir) {
        List<Op> retryable = new ArrayList<>();
        List<Op> abandoned = new ArrayList<>();
        for (Op op : ops) {
            try {
                Path to = instanceDir.resolve(op.to());
                if (op.kind().equals("delete")) {
                    Files.deleteIfExists(to);
                } else {
                    Path from = instanceDir.resolve(op.from());
                    if (!Files.isRegularFile(from)) {
                        // Staged file is missing (already moved by an earlier partial replay or deleted). Report it as
                        // unconfirmed instead of skipping so the caller handles it.
                        abandoned.add(op);
                        continue;
                    }
                    if (!HashUtils.sha256(from).equals(op.sha256())) {
                        abandoned.add(op); // stale staging (rollback race), never place unverified bytes
                        continue;
                    }
                    Files.createDirectories(to.getParent());
                    if (op.kind().equals("rename") || op.kind().equals("seed")) {
                        // Never REPLACE_EXISTING for seed or rename: let FileAlreadyExistsException report the refusal
                        // instead of overwriting the player's file.
                        Files.move(from, to);
                    } else {
                        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } catch (FileAlreadyExistsException theirs) {
                // a refusing kind onto a name the player now owns. Not retryable, because only they can free that
                // name: keeping the journal for it would hold installed_version and re-run the same refusal
                // on every boot for the life of the box, while the next boot's plan reads their file honestly
                abandoned.add(op);
            } catch (IOException e) {
                retryable.add(op);
            }
        }
        return new Result(retryable, abandoned);
    }
}
