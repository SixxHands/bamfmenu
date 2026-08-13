package com.retro.bamfos.updater.engine.report;

import com.retro.bamfos.updater.engine.EngineEntry;
import com.retro.bamfos.updater.engine.config.UpdaterConfig;
import com.retro.bamfos.updater.engine.door.SyncCore;
import com.retro.bamfos.updater.engine.ledger.Ledger;
import com.retro.bamfos.updater.engine.util.HashUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

// Collects typed updater state (no raw paths, logs, or exception messages) so trouble reports can never leak personal info or tokens.
final class BoxFacts {

    /** Entrypoint side passed into TroubleReport, parsed into an enum up front so downstream code
     *  never compares raw strings. */
    enum Side {
        CLIENT("client"), SERVER("server");

        private final String word;

        Side(String word) {
            this.word = word;
        }

        static Side of(String raw) {
            return "server".equals(raw) ? SERVER : CLIENT;
        }

        String word() {
            return word;
        }
    }

    /** Known status values written by SyncCore to last-sync.json; anything else maps to unknown. */
    private static final List<String> SYNC_WORDS = List.of("ok", "staged", "pending", "failed", "offline",
            "limited", "no-release", "token-dead");

    /** Maps raw os.name / os.arch values to normalized names. Checked longest-first so x86_64
     *  doesn't match x86. */
    private static final List<String> OS = List.of("windows", "mac", "linux");
    private static final List<String> ARCH = List.of("aarch64", "x86_64", "amd64", "arm", "x86");

    private static final String ENGINE_JAR = "bamfos-updater-engine.jar";

    /** Info about a single core jar generation. A 0-byte jar usually means antivirus truncated it,
     *  so recording size helps diagnose that. */
    record CoreJar(Shape gen, long bytes, long at) {
    }

    /** State of the engine jar files on disk. A leftover .new file across boots usually points to
     *  a file lock or antivirus interference. */
    record EngineSlot(boolean jar, boolean good, boolean staged, Shape sha, long bytes, long at) {
    }

    /** Flat snapshot of install facts in the exact row order printed by trouble reports. */
    record Box(Side side, Shape os, Shape arch, int java, Shape engineBuild, Shape packVersion,
               Shape releaseLock, boolean repoChanged, int pins, int managed, int disabled, int quarantined,
               Shape lastSync, boolean filesHeldBack, boolean journal,
               List<CoreJar> cores, EngineSlot engine) {
    }

    static Box read(Path dir, Side side) {
        Ledger ledger = ledger(dir);
        UpdaterConfig cfg = config(dir);
        return new Box(side,
                // Prefix match, not equals: os.name is "Windows 11" or "Mac OS X", while Linux is bare.
                Shape.ourPrefix(prop("os.name"), OS),
                Shape.ourPrefix(prop("os.arch"), ARCH),
                Runtime.version().feature(),
                Shape.tag(EngineEntry.version()),
                ledger.installedVersion.isEmpty() ? Shape.NONE : Shape.tag(ledger.installedVersion),
                cfg.tagPin().isEmpty() ? Shape.NONE : Shape.tag(cfg.tagPin()),
                !UpdaterConfig.DEFAULT_REPO.equals(cfg.repo()),
                cfg.pins().size(),
                ledger.files.size(),
                (int) ledger.files.values().stream().filter(e -> e.disabled && !e.quarantined).count(),
                (int) ledger.files.values().stream().filter(e -> e.quarantined).count(),
                Shape.ourWord(SyncCore.lastSyncResult(dir), SYNC_WORDS),
                SyncCore.lastSyncHeldFiles(dir),
                Files.isRegularFile(own(dir).resolve("journal.json")),
                cores(dir), engine(dir));
    }

    /** Names only, and only the ones that pass the generation gate: a filename is one of the few things on
     *  that box a player can be talked into creating, and core-<40 hex>.jar is a credential wearing one. */
    private static List<CoreJar> cores(Path dir) {
        List<CoreJar> out = new ArrayList<>();
        try (Stream<Path> listing = Files.list(own(dir).resolve("core"))) {
            for (Path p : listing.filter(Files::isRegularFile).sorted().toList()) {
                Shape gen = Shape.gen(p.getFileName().toString());
                if (gen != Shape.UNKNOWN) {
                    out.add(new CoreJar(gen, bytes(p), p.toFile().lastModified()));
                }
            }
        } catch (IOException | RuntimeException none) {
            return out;
        }
        return out;
    }

    private static EngineSlot engine(Path dir) {
        Path slot = own(dir).resolve("engine");
        Path jar = slot.resolve(ENGINE_JAR);
        return new EngineSlot(Files.isRegularFile(jar),
                Files.isRegularFile(slot.resolve(ENGINE_JAR + ".good")),
                Files.isRegularFile(slot.resolve(ENGINE_JAR + ".new")),
                sha(jar), bytes(jar), jar.toFile().lastModified());
    }

    /** Computes the SHA-256 directly from disk instead of trusting a cached marker file. */
    private static Shape sha(Path p) {
        try {
            return Shape.sha(HashUtils.sha256(p));
        } catch (IOException | RuntimeException unreadable) {
            return Shape.UNKNOWN;
        }
    }

    private static long bytes(Path p) {
        try {
            return Files.size(p);
        } catch (IOException | RuntimeException gone) {
            return -1L;
        }
    }

    private static Ledger ledger(Path dir) {
        try {
            return Ledger.load(Ledger.pathIn(dir));
        } catch (IOException | RuntimeException unreadable) {
            return new Ledger();
        }
    }

    private static UpdaterConfig config(Path dir) {
        try {
            return UpdaterConfig.load(dir);
        } catch (IOException | RuntimeException unreadable) {
            return new UpdaterConfig("", UpdaterConfig.DEFAULT_REPO, "", 0, List.of(), "", true);
        }
    }

    /** TWO NAMED PROPERTIES reach this class and System.getProperties is never enumerated, because a launcher's
     *  property table is full of other people's paths and tokens. */
    private static String prop(String key) {
        String v = System.getProperty(key);
        return v == null ? "" : v;
    }

    private static Path own(Path dir) {
        return dir.resolve(".bamfos-updater");
    }

    private BoxFacts() {
    }
}
