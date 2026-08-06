package com.retro.bamfos.updater.engine.journal;

import com.retro.bamfos.updater.engine.json.JsonWriter;
import com.retro.bamfos.updater.engine.json.MiniJson;
import com.retro.bamfos.updater.engine.util.HashUtils;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
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
    public record Op(String kind, String from, String to, String sha256, int attempts) {

        /** Entry written by the planner; retry counts are tracked separately by the journal. */
        public Op(String kind, String from, String to, String sha256) {
            this(kind, from, to, sha256, 0);
        }

        Op tried() {
            return new Op(kind, from, to, sha256, attempts + 1);
        }
    }

    /**
     * Boots to retry a stuck op before giving up. Three boots clears transient AV or launcher locks;
     * anything surviving past that is permanently locked.
     */
    public static final int MAX_ATTEMPTS = 3;

    /** Split of replay results into ops to retry next boot vs ops that hit the retry cap. Shared by both
     *  replay call sites so they stay consistent. */
    public record Stuck(List<Op> keep, List<Op> gaveUp) {
    }

    /**
     * Bumps attempt counts on retryable ops and drops any that ran out of tries so a permanently locked
     * file doesn't retry forever. Dropped ops stay out of the ledger (and still block installed_version
     * this run), so the next plan re-derives them from disk.
     */
    public static Stuck afterAttempt(List<Op> retryable) {
        List<Op> keep = new ArrayList<>();
        List<Op> gaveUp = new ArrayList<>();
        for (Op op : retryable) {
            Op tried = op.tried();
            (tried.attempts() >= MAX_ATTEMPTS ? gaveUp : keep).add(tried);
        }
        return new Stuck(keep, gaveUp);
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

    /** Progress callback invoked right before each op starts (`done` is 1-based, starting at 1 of n). */
    public interface Watcher {
        void at(Op op, int done, int total);
    }

    private static final Watcher QUIET = (op, done, total) -> {
    };

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
                        // Missing attempts defaults to 0 so journals from older engines still load cleanly.
                        j.ops.add(new Op(str(om.get("kind")), str(om.get("from")), str(om.get("to")),
                                str(om.get("sha256")), num(om.get("attempts"))));
                    }
                }
            }
        }
        return j;
    }

    private static String str(Object v) {
        return v instanceof String s ? s : "";
    }

    /** Parses the attempts count, falling back to 0 if a hand-edited journal has a non-numeric value. */
    private static int num(Object v) {
        return v instanceof Number n ? Math.max(0, n.intValue()) : 0;
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
            // Omit attempts when 0 so fresh journals stay clean and match the older format (older engines ignore
            // unknown keys anyway).
            if (op.attempts() > 0) {
                om.put("attempts", (long) op.attempts());
            }
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
        return replay(instanceDir, QUIET);
    }

    /** Same as replay(), but reports each op as it starts for progress bars (factory resets can have
     *  thousands of ops and would look hung without progress output). */
    public Result replay(Path instanceDir, Watcher watcher) {
        List<Op> retryable = new ArrayList<>();
        List<Op> abandoned = new ArrayList<>();
        int done = 0;
        for (Op op : ops) {
            watcher.at(op, ++done, ops.size());
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
            } catch (InvalidPathException garbage) {
                // Valid JSON can still name an OS-illegal path; catch the unchecked exception and abandon it so
                // it doesn't crash step 0 on every boot.
                abandoned.add(op);
            }
        }
        return new Result(retryable, abandoned);
    }

    /**
     * Verifies on disk that the given ops actually landed (used after offline resets when no sync runs).
     * Checks deletes only when no later op in journal order re-places that path; checks every placement
     * so duplicate targets are caught.
     */
    public static List<Op> verify(Path instanceDir, List<Op> ops, Watcher watcher) {
        Map<String, Integer> lastPlaced = new HashMap<>();
        for (int i = 0; i < ops.size(); i++) {
            if (!ops.get(i).kind().equals("delete")) {
                lastPlaced.put(ops.get(i).to(), i);
            }
        }
        List<Op> wrong = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            watcher.at(op, i + 1, ops.size());
            Path to = instanceDir.resolve(op.to());
            try {
                if (op.kind().equals("delete")) {
                    if (lastPlaced.getOrDefault(op.to(), -1) < i && Files.exists(to)) {
                        wrong.add(op);
                    }
                } else if (!Files.isRegularFile(to) || !HashUtils.sha256(to).equals(op.sha256())) {
                    wrong.add(op);
                }
            } catch (IOException unreadable) {
                // If we can't hash the file, treat it as unverified.
                wrong.add(op);
            }
        }
        return wrong;
    }
}
