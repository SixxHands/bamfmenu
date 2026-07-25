package com.retro.bamfos.updater.engine.ops;

import com.retro.bamfos.updater.engine.bus.BootBus;
import com.retro.bamfos.updater.engine.config.UpdaterConfig;
import com.retro.bamfos.updater.engine.door.Doors;
import com.retro.bamfos.updater.engine.fetch.HttpFetcher;
import com.retro.bamfos.updater.engine.journal.InstanceLock;
import com.retro.bamfos.updater.engine.json.MiniJson;
import com.retro.bamfos.updater.engine.ledger.Ledger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The BIOS-facing surface. The BIOS is a view; THIS is the single writer -- menu code
 * never touches manifests, ledgers, or files, it calls here. By menu time jars are loaded and
 * locked, so every sync is STAGED: journal written, applies next boot, "changes staged" is always
 * the honest answer. Return codes over exceptions because callers sit across a reflective boundary:
 * 0 ok/staged, 1 no target to sync to, 2 failed, 3 another sync holds the lock, 4 token dead.
 */
public final class UpdaterOps {

    private final Path dir;
    private final String apiBase;

    public UpdaterOps(Path dir, String apiBase) {
        this.dir = dir;
        this.apiBase = apiBase;
    }

    /** Returns BIOS status fields: version, pin, token state, last sync, and counts. */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Ledger ledger = Ledger.load(Ledger.pathIn(dir));
            UpdaterConfig cfg = UpdaterConfig.load(dir);
            out.put("version", Doors.localVersion(dir).orElse(ledger.installedVersion));
            out.put("pinned", cfg.tagPin());
            out.put("token_dead", Files.exists(dir.resolve(".bamfos-updater").resolve("token-dead")));
            long disabled = ledger.files.values().stream().filter(e -> e.disabled && !e.quarantined).count();
            long quarantined = ledger.files.values().stream().filter(e -> e.quarantined).count();
            out.put("disabled_count", disabled);
            out.put("quarantined_count", quarantined);
            Path last = dir.resolve(".bamfos-updater").resolve("last-sync.json");
            if (Files.isRegularFile(last) && MiniJson.parse(Files.readString(last, StandardCharsets.UTF_8)) instanceof Map<?, ?> m) {
                out.put("last_sync_at", m.get("at") instanceof Long l ? l : 0L);
                out.put("last_sync_result", m.get("result") instanceof String s ? s : "");
            }
        } catch (Exception e) {
            out.put("error", String.valueOf(e));
        }
        return out;
    }

    /** Stages a sync to the pinned version, or the version currently broadcast by the server. */
    public int syncNow() {
        try {
            UpdaterConfig cfg = UpdaterConfig.load(dir);
            String tag = !cfg.tagPin().isEmpty() ? cfg.tagPin()
                    : Doors.broadcast(dir, cfg).orElse("");
            if (tag.isEmpty()) {
                return 1;
            }
            return stagedSync(cfg, tag, false);
        } catch (IOException e) {
            return 2;
        }
    }

    /** Writes the tag= pin and stages a sync to it. The UI handles showing the PINNED warning. */
    public int syncTo(String tag) {
        try {
            writePin(tag);
            return stagedSync(UpdaterConfig.load(dir), tag, false);
        } catch (IOException e) {
            return 2;
        }
    }

    /** Full hash audit vs the INSTALLED version's manifest, staged. */
    public int repair() {
        try {
            UpdaterConfig cfg = UpdaterConfig.load(dir);
            String tag = !cfg.tagPin().isEmpty() ? cfg.tagPin()
                    : Ledger.load(Ledger.pathIn(dir)).installedVersion;
            if (tag.isEmpty()) {
                return 1;
            }
            return stagedSync(cfg, tag, true);
        } catch (IOException e) {
            return 2;
        }
    }

    /** The optional-mods page's data: category=optional entries off the cached manifest, current
     *  disk/ledger bits, plus every quarantined jar with its recorded why. */
    public List<Map<String, Object>> listOptional() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Ledger ledger = Ledger.load(Ledger.pathIn(dir));
            Path cached = dir.resolve(".bamfos-updater").resolve("manifest-current.json");
            if (Files.isRegularFile(cached)
                    && MiniJson.parse(Files.readString(cached, StandardCharsets.UTF_8)) instanceof Map<?, ?> m
                    && m.get("files") instanceof List<?> files) {
                for (Object f : files) {
                    if (f instanceof Map<?, ?> fm && "optional".equals(fm.get("category"))
                            && fm.get("path") instanceof String path) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("path", path);
                        row.put("disabled", !Files.isRegularFile(dir.resolve(path))
                                && Files.isRegularFile(dir.resolve(path + ".disabled")));
                        row.put("quarantined", false);
                        out.add(row);
                    }
                }
            }
            for (Map.Entry<String, Ledger.Entry> e : ledger.files.entrySet()) {
                if (e.getValue().quarantined) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("path", e.getKey());
                    row.put("disabled", !Files.isRegularFile(dir.resolve(e.getKey())));
                    row.put("quarantined", true);
                    row.put("reason", e.getValue().quarantineReason == null ? "" : e.getValue().quarantineReason);
                    out.add(row);
                }
            }
        } catch (Exception ignored) {
            // an unreadable cache lists nothing; the page renders empty, nothing breaks
        }
        return out;
    }

    /** Flips the filename bit THROUGH the engine so the ledger stays coherent. True =
     *  the state now matches the ask (idempotent); false = nothing sane to flip (both names on
     *  disk is crash debris and the reconciler's business, neither name means no such mod). */
    public boolean setDisabled(String path, boolean disabled) {
        try (var lock = InstanceLock.acquire(dir).orElse(null)) {
            if (lock == null) {
                return false;
            }
            Path enabledFile = dir.resolve(path);
            Path off = dir.resolve(path + ".disabled");
            boolean hasEnabled = Files.isRegularFile(enabledFile);
            boolean hasOff = Files.isRegularFile(off);
            if (hasEnabled == hasOff) {
                return false;
            }
            if (disabled && hasEnabled) {
                Files.move(enabledFile, off);
            } else if (!disabled && hasOff) {
                Files.move(off, enabledFile);
            }
            Ledger ledger = Ledger.load(Ledger.pathIn(dir));
            Ledger.Entry en = ledger.files.get(path);
            if (en != null && en.disabled != disabled) {
                en.disabled = disabled;
                ledger.save(Ledger.pathIn(dir));
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** The cached notes body: current release's, or the just-applied one after a sync. */
    public String releaseNotes() {
        try {
            Path p = dir.resolve(".bamfos-updater").resolve("release-notes.json");
            if (Files.isRegularFile(p)
                    && MiniJson.parse(Files.readString(p, StandardCharsets.UTF_8)) instanceof Map<?, ?> m
                    && m.get("body") instanceof String body) {
                return body;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private int stagedSync(UpdaterConfig cfg, String tag, boolean repair) {
        Optional<InstanceLock> lock;
        try {
            lock = InstanceLock.acquire(dir);
        } catch (IOException e) {
            return 2;
        }
        if (lock.isEmpty()) {
            return 3;
        }
        try (InstanceLock ignored = lock.get()) {
            String from = Doors.localVersion(dir).orElse("");
            return Doors.stagedClientSync(dir, cfg, tag, from, repair, apiBase, BootBus.at(dir));
        } catch (HttpFetcher.TokenDead dead) {
            return 4;
        } catch (Exception e) {
            return 2;
        }
    }

    /** Rewrites only the tag= line of the properties file; every other line is the human's. */
    private void writePin(String tag) throws IOException {
        Path p = dir.resolve(".bamfos-updater").resolve("bamfos-updater.properties");
        List<String> lines = new ArrayList<>();
        if (Files.isRegularFile(p)) {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (!line.trim().startsWith("tag=")) {
                    lines.add(line);
                }
            }
        }
        if (!tag.isEmpty()) {
            lines.add("tag=" + tag);
        }
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.write(tmp, lines, StandardCharsets.UTF_8);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
    }
}
