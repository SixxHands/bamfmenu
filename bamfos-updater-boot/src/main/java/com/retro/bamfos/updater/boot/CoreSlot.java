package com.retro.bamfos.updater.boot;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;

/* Picks a core-NNNN.jar from .bamfos-updater/core/ (highest generation first, 4-digit padded so lexical
 * sort is numeric), loads it in an isolated classloader, and hands over. Falls back to the bundled floor
 * core-0000.jar if nothing else loads. */
final class CoreSlot {

    private static final String MARK = "core-unproven";
    private static final String PREFIX = "core-";
    private static final String FLOOR = "core-0000.jar";
    private static final String BASELINE = "/baseline/bamfos-updater-core.jar";

    // JVM property instead of a static field: if someone drops a second shim jar in mods/, FML loads both in separate classloaders and they'd race without a shared flag.
    private static final String ONCE = "bamfos.updater.core.ran";

    /** Runs the newest working core without throwing. An empty map means no core ran, so boot anyway. */
    static Map<?, ?> run(Path instanceDir, String[] args) {
        Path dir = instanceDir.resolve(".bamfos-updater").resolve("core");
        List<Path> order = candidates(dir);
        String[] mark = read(dir.resolve(MARK));
        boolean sibling = writerStillRunning(mark);
        String blamed = sibling ? "" : blamed(mark, order);
        if (!blamed.isEmpty()) {
            // Skip the blamed generation and anything newer than it. Skipping only an exact match would
            // flip-flop between the top two forever, so nothing would ever get struck or deleted.
            Path last = order.isEmpty() ? null : order.get(order.size() - 1);
            order.removeIf(p -> name(p).compareTo(blamed) >= 0);
            if (order.isEmpty() && last != null) {
                // Fallback so we always have something to run: if all other cores fail, re-extract and run the bundled core-0000.jar.
                Path floor = name(last).equals(FLOOR) ? last : extract(dir);
                order.add(floor != null ? floor : last);
            }
        }
        for (Path jar : order) {
            Map<?, ?> answer = attempt(dir, jar, blamed, sibling, args);
            if (answer != null) {
                return answer;
            }
        }
        Path floor = extract(dir);
        Map<?, ?> answer = floor == null ? null : attempt(dir, floor, blamed, sibling, args);
        return answer == null ? Map.of() : answer;
    }

    /**
     * Client entrypoint: runs once per JVM and binds the ops handle onto the BIOS bridge.
     * Kept here instead of ClientLocator so the double-promotion guard is testable outside FML.
     */
    static void clientBoot(Path gameDir) {
        if (System.getProperty(ONCE) != null) {
            return;
        }
        System.setProperty(ONCE, "1");
        Map<?, ?> answer = run(gameDir, new String[]{"--client", "--dir", gameDir.toString()});
        if (answer.get("ops") instanceof BiFunction<?, ?, ?> ops) {
            UpdaterBridge.bindOps(cast(ops));
        }
    }

    /**
     * The core creates this as a BiFunction from java.base so it crosses the isolated classloader.
     * Older cores or engines may omit the key, and the BIOS treats that as not installed.
     */
    @SuppressWarnings("unchecked")
    private static BiFunction<String, Object[], Object> cast(BiFunction<?, ?, ?> f) {
        return (BiFunction<String, Object[], Object>) f;
    }

    /**
     * Highest generation first; 4-digit padding makes lexical sort match numeric order.
     * Strict core-NNNN.jar match ignores hand-copied backups (e.g. core-old.jar sorts above digits) without deleting them.
     * Uses char checks instead of a static Pattern so this jar stays free of class initialisers (FrozenStubJarTest).
     */
    private static List<Path> candidates(Path dir) {
        try (Stream<Path> list = Files.list(dir)) {
            return new ArrayList<>(list
                    .filter(p -> isGeneration(name(p)))
                    .sorted(Comparator.comparing(CoreSlot::name).reversed())
                    .toList());
        } catch (IOException | RuntimeException none) {
            return new ArrayList<>(); // Missing dir or permissions: fall back to the embedded core.
        }
    }

    /** Strict filename format: core-NNNN.jar (four digits). */
    private static boolean isGeneration(String n) {
        if (n.length() != 13 || !n.startsWith(PREFIX) || !n.endsWith(".jar")) {
            return false;
        }
        for (int i = 5; i < 9; i++) {
            if (n.charAt(i) < '0' || n.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** Returns null if there's no mark, or an empty array if the mark exists but can't be read
     *  (still treated as armed, matching EngineGuard). */
    private static String[] read(Path f) {
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            return Files.readString(f, StandardCharsets.UTF_8).trim().split("\t");
        } catch (IOException | RuntimeException unreadable) {
            return new String[0];
        }
    }

    /** Returns the generation to skip. If the mark exists, it's armed and field 1 says where to
     *  start skipping. An unparseable mark blames the newest jar on disk so garbage only skips one
     *  generation instead of pinning us to the fallback or disabling the guard. */
    private static String blamed(String[] mark, List<Path> order) {
        if (mark == null) {
            return "";
        }
        if (mark.length >= 4 && mark[0].equals("v1") && !mark[1].trim().isEmpty()) {
            return mark[1].trim();
        }
        return order.isEmpty() ? "" : name(order.get(0));
    }

    /**
     * True if the process that armed this mark is still alive (a sibling instance mid-boot, so don't demote it).
     * Returns false for unparsable marks so we judge rather than skip, matching EngineGuard.writerStillRunning.
     */
    private static boolean writerStillRunning(String[] mark) {
        if (mark == null || mark.length < 4) {
            return false;
        }
        try {
            long pid = Long.parseLong(mark[2].trim());
            long started = Long.parseLong(mark[3].trim());
            if (pid <= 0 || started <= 0 || pid == ProcessHandle.current().pid()) {
                return false;
            }
            return ProcessHandle.of(pid)
                    .filter(ProcessHandle::isAlive)
                    .flatMap(h -> h.info().startInstant())
                    .map(when -> when.toEpochMilli() == started)
                    .orElse(false);
        } catch (RuntimeException junk) {
            return false;
        }
    }

    /** Returns null if the jar failed to load so we try an older one; any map means it ran and we stop. */
    private static Map<?, ?> attempt(Path dir, Path jar, String blamed, boolean sibling, String[] args) {
        URLClassLoader loader = null;
        Method enter;
        try {
            loader = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader());
            // Run class init during loading (initialize=true) so a broken static block or newer Java bytecode
            // fails here and falls back to an older core instead of throwing inside invoke() as if it ran.
            enter = Class.forName(UpdaterAbi.CORE_ENTRY_CLASS, true, loader)
                    .getMethod(UpdaterAbi.CORE_ENTRY_METHOD, String[].class);
        } catch (Throwable wontLoad) {
            // Close the loader so Windows doesn't keep the jar locked and block re-extracting the fallback.
            close(loader);
            System.out.println("[updater] core " + name(jar) + " will not load: " + wontLoad);
            return null;
        }
        if (!sibling) {
            // Arm after loading and before calling enter(): arming before load lets a corrupt jar blame the
            // fallback, and arming after enter() won't catch a hang. Skip arming if a mark from another
            // instance is active so we don't overwrite it.
            arm(dir, name(jar), blamed);
        }
        try {
            Object answer = enter.invoke(null, (Object) args);
            return answer instanceof Map<?, ?> m ? m : Map.of();
        } catch (Throwable ranAndFailed) {
            // Return an empty map instead of null because this core already ran; we don't want to start a
            // second core in the same boot. Leave the mark so the next boot skips it.
            System.out.println("[updater] sync skipped: " + ranAndFailed);
            return Map.of();
        }
    }

    // Format: v1\trunning\tpid\tpidStartMillis\tskipped. Stub only reads 0..3; core reads 4 to know what to strike ("-" when we had to run the last jar standing).
    private static void arm(Path dir, String running, String blamed) {
        try {
            Files.createDirectories(dir);
            ProcessHandle self = ProcessHandle.current();
            String skipped = blamed.isEmpty() || blamed.equals(running) ? "-" : blamed;
            Files.writeString(dir.resolve(MARK), "v1\t" + running + "\t" + self.pid() + "\t"
                    + self.info().startInstant().map(Instant::toEpochMilli).orElse(0L) + "\t" + skipped,
                    StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unarmed) {
            // If we can't arm the mark, continue unguarded rather than failing the boot.
        }
    }

    // Extracts bundled core-0000.jar. Uses a pid-suffixed tmp file since double launches run before any InstanceLock exists.
    private static Path extract(Path dir) {
        Path target = dir.resolve(FLOOR);
        Path tmp = dir.resolve(FLOOR + "." + ProcessHandle.current().pid() + ".tmp");
        try (InputStream in = CoreSlot.class.getResourceAsStream(BASELINE)) {
            if (in == null) {
                return null; // a build with no baseline core: caught by the jar-content gate, not by a player
            }
            Files.createDirectories(dir);
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException | RuntimeException disk) {
            sweepTmp(tmp); // Clean up our pid-named temp file on failure so we don't leave junk behind.
            System.out.println("[updater] no core could be loaded: " + disk);
            return null;
        }
    }

    private static void sweepTmp(Path tmp) {
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException | RuntimeException held) {
            // Best-effort cleanup; ignore delete failures.
        }
    }

    private static void close(URLClassLoader loader) {
        try {
            if (loader != null) {
                loader.close();
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static String name(Path p) {
        return p.getFileName().toString();
    }

    private CoreSlot() {
    }
}
