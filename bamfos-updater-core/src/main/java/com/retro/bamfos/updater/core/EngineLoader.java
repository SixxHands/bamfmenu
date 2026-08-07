package com.retro.bamfos.updater.core;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;

/* Loads the engine jar in an isolated URLClassLoader (platform parent only, so it sees the JDK and
 * nothing from FML or mods). Swaps in any staged engine.jar.new first, falling back to the embedded
 * baseline once if the jar on disk won't load. */
public final class EngineLoader {

    /** A loaded engine: call enter(args) exactly like EngineEntry documents. */
    public record Engine(URLClassLoader loader, Method enter) {
        public int enter(String[] args) throws Exception {
            return (Integer) enter.invoke(null, (Object) args);
        }
    }

    /**
     * Ensures an engine jar exists (extracting the embedded baseline if missing) and loads it.
     * Retries once with a fresh baseline extract if the on-disk jar fails to load.
     */
    public static Engine ensureAndLoad(Path enginePath, Supplier<InputStream> baseline) throws IOException {
        // Check probation first before modifying the slot so we evaluate the previous engine before
        // promoting a new one.
        EngineGuard.rollbackUnproven(enginePath, baseline);
        promoteStaged(enginePath);
        if (!Files.isRegularFile(enginePath)) {
            extract(enginePath, baseline); // Fatal if no engine is available and there's nothing to fall back to.
        }
        try {
            return load(enginePath);
        } catch (ReflectiveOperationException | IOException | LinkageError first) {
            // strike the broken jar BEFORE re-extracting the baseline (which clears probation),
            // otherwise unloadable releases (e.g. wrong Java LinkageError) re-download and fail every boot
            EngineGuard.strike(enginePath, EngineGuard.sha256(enginePath));
            extract(enginePath, baseline);
            try {
                return load(enginePath);
            } catch (ReflectiveOperationException | IOException | LinkageError second) {
                throw new IOException("baseline engine failed to load", second);
            }
        }
    }

    /**
     * Promotes a staged engine.jar.new before loading. Swap failures (e.g. Windows lock from a
     * second instance or AV scan) are logged and ignored so the current engine still loads.
     */
    private static void promoteStaged(Path enginePath) {
        Path staged = enginePath.resolveSibling(enginePath.getFileName() + ".new");
        if (!Files.isRegularFile(staged)) {
            return;
        }
        String sha = EngineGuard.sha256(staged);
        if (EngineGuard.rejectedBefore(enginePath, sha)) {
            return; // Skip staged jar if its hash was already rejected.
        }
        try {
            Files.createDirectories(enginePath.getParent());
            EngineGuard.keepKnownGood(enginePath); // Back up the current jar before swapping so we can roll back to it.
            Files.move(staged, enginePath, StandardCopyOption.REPLACE_EXISTING);
            EngineGuard.arm(enginePath, sha); // Arm probation until the new engine finishes booting and clears it.
        } catch (IOException held) {
            System.out.println("[updater] staged engine could not be swapped in, "
                    + "running the one on disk: " + held);
        }
    }

    public static Engine load(Path jar) throws IOException, ReflectiveOperationException {
        URLClassLoader loader = new URLClassLoader(
                new URL[]{jar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
        try {
            Class<?> entry = Class.forName(CoreAbi.ENGINE_ENTRY_CLASS, false, loader);
            Method enter = entry.getMethod(CoreAbi.ENGINE_ENTRY_METHOD, String[].class);
            return new Engine(loader, enter);
        } catch (Throwable t) {
            // catch Throwable (not just ReflectiveOperationException) so LinkageError still closes
            // the URLClassLoader; otherwise Windows keeps the bad jar locked and blocks baseline retry
            try {
                loader.close();
            } catch (IOException ignored) {
            }
            throw t;
        }
    }

    /** Atomic extract: temp file beside the target, then move, a crash never leaves a half jar. */
    static void extract(Path target, Supplier<InputStream> baseline) throws IOException {
        Files.createDirectories(target.getParent());
        // Include our pid in the temp filename because this runs before InstanceLock; a shared .tmp name
        // would let two concurrent launches overwrite or promote each other's half-written jar.
        Path tmp = target.resolveSibling(target.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        try {
            try (InputStream in = baseline.get()) {
                if (in == null) {
                    throw new IOException("embedded baseline engine resource is missing from the shim jar");
                }
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            replaceAtomic(tmp, target);
        } catch (IOException disk) {
            try {
                Files.deleteIfExists(tmp); // Clean up our pid-named temp file on failure.
            } catch (IOException ignored) {
            }
            throw disk;
        }
        // Clear probation after extracting the embedded baseline so it doesn't inherit a failed flag
        // from the jar it replaced.
        EngineGuard.disarm(target);
    }

    /**
     * Uses ATOMIC_MOVE so Windows doesn't delete-then-move (which leaves the slot empty on crash),
     * falling back to REPLACE_EXISTING only on filesystems without atomic rename.
     */
    static void replaceAtomic(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException odd) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Checks resources inside the core jar first (usually absent in shipped cores to avoid nesting,
     * but present in tests and bundled fallback builds). Returns null if absent.
     */
    public static Supplier<InputStream> embeddedBaseline() {
        return () -> EngineLoader.class.getResourceAsStream("/baseline/bamfos-updater-engine.jar");
    }

    private EngineLoader() {
    }
}
