package com.retro.bamfos.updater.engine.ledger;

import com.retro.bamfos.updater.engine.json.JsonWriter;
import com.retro.bamfos.updater.engine.json.MiniJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

// Tracks only updater-managed files keyed by manifest path (disabled state is stored as a flag). Atomic save commits the sync and closes the journal.
public final class Ledger {

    /** Recorded state for a managed file. `quarantined` marks jars disabled by the join guard (once per jar),
     *  `quarantineReason` is the reason shown in the BIOS optional page, and `mergeName` stores the
     *  servers.dat entry name for merge-servers seeds so we can check presence without re-fetching. */
    public static final class Entry {
        public String sha256;
        public boolean seedOnce;
        public boolean disabled;
        public boolean orphanOnRemove;
        public boolean quarantined;
        public String quarantineReason;
        public String mergeName;

        public Entry(String sha256) {
            this.sha256 = sha256;
        }
    }

    public String packId = "bamfos";
    public String installedVersion = "";
    public final Map<String, Entry> files = new LinkedHashMap<>();

    public static Path pathIn(Path instanceDir) {
        return instanceDir.resolve(".bamfos-updater").resolve("ledger.json");
    }

    public static Ledger load(Path file) throws IOException {
        Ledger l = new Ledger();
        if (!Files.isRegularFile(file)) {
            return l; // absent ledger = fresh seed; the reconciler adopts/repairs from hashes
        }
        Object root = MiniJson.parse(Files.readString(file, StandardCharsets.UTF_8));
        if (!(root instanceof Map<?, ?> m)) {
            return l;
        }
        l.packId = str(m.get("pack_id"), l.packId);
        l.installedVersion = str(m.get("installed_version"), "");
        if (m.get("files") instanceof Map<?, ?> fm) {
            for (Map.Entry<?, ?> e : fm.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> em) {
                    Entry en = new Entry(str(em.get("sha256"), ""));
                    en.seedOnce = Boolean.TRUE.equals(em.get("seed_once"));
                    en.disabled = Boolean.TRUE.equals(em.get("disabled"));
                    en.orphanOnRemove = Boolean.TRUE.equals(em.get("orphan_on_remove"));
                    en.quarantined = Boolean.TRUE.equals(em.get("quarantined"));
                    en.quarantineReason = em.get("quarantine_reason") instanceof String qr ? qr : null;
                    en.mergeName = em.get("merge_name") instanceof String mn ? mn : null;
                    l.files.put(String.valueOf(e.getKey()), en);
                }
            }
        }
        return l;
    }

    public void save(Path file) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("ledger_version", 1L);
        root.put("pack_id", packId);
        root.put("installed_version", installedVersion);
        Map<String, Object> fm = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> e : files.entrySet()) {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("sha256", e.getValue().sha256);
            if (e.getValue().seedOnce) {
                em.put("seed_once", true);
            }
            if (e.getValue().disabled) {
                em.put("disabled", true);
            }
            if (e.getValue().orphanOnRemove) {
                em.put("orphan_on_remove", true);
            }
            if (e.getValue().quarantined) {
                em.put("quarantined", true);
            }
            if (e.getValue().quarantineReason != null) {
                em.put("quarantine_reason", e.getValue().quarantineReason);
            }
            if (e.getValue().mergeName != null) {
                em.put("merge_name", e.getValue().mergeName);
            }
            fm.put(e.getKey(), em);
        }
        root.put("files", fm);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, JsonWriter.write(root), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String str(Object v, String dflt) {
        return v instanceof String s ? s : dflt;
    }
}
