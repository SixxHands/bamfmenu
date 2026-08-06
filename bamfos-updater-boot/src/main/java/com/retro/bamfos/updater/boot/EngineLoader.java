package com.retro.bamfos.updater.boot;

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

/**
 * Loads the engine jar in an ISOLATED classloader and hands over control.
 * The gap. The engine must be hot-updatable (it is ordinary manifest cargo), so the shim can
 * never link against it at compile time -- the only compile-time coupling allowed is the reflective
 * ABI in UpdaterAbi. The guard. Parent is the PLATFORM classloader: the engine sees the JDK
 * and nothing else -- not the shim's classes, not FML, not any mod. A broken on-disk engine falls
 * back to the embedded baseline once, then loads that, and a staged engine.jar.new that
 * cannot be swapped in never costs the engine already on disk. A promoted engine that LOADS and then does
 * not WORK is a different failure that this path cannot see at all, so EngineGuard judges that one.
 */
public final class EngineLoader {

    /** A loaded engine: call enter(args) exactly like EngineEntry documents. */
    public record Engine(URLClassLoader loader, Method enter) {
        public int enter(String[] args) throws Exception {
            return (Integer) enter.invoke(null, (Object) args);
        }
    }

    /**
     * Makes sure an engine jar exists at enginePath, extracting the embedded baseline when absent,
     * then loads it. A jar that exists but will not load (truncated download, disk damage) gets ONE
     * baseline re-extract before giving up -- the baseline then self-updates via the normal sync.
     */
    public static Engine ensureAndLoad(Path enginePath, Supplier<InputStream> baseline) throws IOException {
        // FIRST, before anything else touches the slot: the last boot's engine gets judged on its own record,
        // never on a promotion this boot is about to make (EngineGuard)
        EngineGuard.rollbackUnproven(enginePath, baseline);
        promoteStaged(enginePath);
        if (!Files.isRegularFile(enginePath)) {
            extract(enginePath, baseline); // no engine at all IS fatal, there is nothing to fall back to
        }
        try {
            return load(enginePath);
        } catch (ReflectiveOperationException | IOException | LinkageError first) {
            // corrupt on-disk engine: re-extract the baseline once and retry; a second failure is real.
            // Strike the bytes BEFORE the re-extract, because extract disarms probation (the baseline is not on
            // trial) and without a record of this the same asset gets re-downloaded, re-promoted and
            // re-rejected on every single boot for the life of that release: a release naming an engine built
            // for a newer Java throws LinkageError here every time
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
     * Swaps in a sync-staged engine.jar.new, because the engine cannot replace itself while loaded, same
     * contract as the wrapper's shim swap line. A failed swap is logged and SURVIVED, never
     * thrown: a second instance of the same profile holds engine.jar open for its whole session (load()
     * deliberately never closes that loader) and Windows refuses to replace an open file, as does an AV
     * scanning the fresh download. Letting that escape costs the engine already on disk, which was fine
     * the whole time, so the session gets no sync, no ops binding and a BIOS reading NOT INSTALLED. The
     * next boot retries the same swap for free.
     */
    private static void promoteStaged(Path enginePath) {
        Path staged = enginePath.resolveSibling(enginePath.getFileName() + ".new");
        if (!Files.isRegularFile(staged)) {
            return;
        }
        String sha = EngineGuard.sha256(staged);
        if (EngineGuard.rejectedBefore(enginePath, sha)) {
            return; // these exact bytes already had their one probation and bricked the sync
        }
        try {
            Files.createDirectories(enginePath.getParent());
            EngineGuard.keepKnownGood(enginePath); // BEFORE the move: the displaced jar IS the rollback image
            Files.move(staged, enginePath, StandardCopyOption.REPLACE_EXISTING);
            EngineGuard.arm(enginePath, sha); // and the new one runs ON PROBATION until it says otherwise
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
            Class<?> entry = Class.forName(UpdaterAbi.ENGINE_ENTRY_CLASS, false, loader);
            Method enter = entry.getMethod(UpdaterAbi.ENGINE_ENTRY_METHOD, String[].class);
            return new Engine(loader, enter);
        } catch (Throwable t) {
            // catch THROWABLE, not just ReflectiveOperationException: a wrong-Java or truncated
            // engine jar throws a LinkageError (UnsupportedClassVersionError / ClassFormatError),
            // and if the loader isn't closed Windows locks the jar so the baseline re-extract retry
            // in ensureAndLoad can't replace it -- the one case the retry exists for
            try {
                loader.close();
            } catch (IOException ignored) {
            }
            throw t;
        }
    }

    /** Atomic extract: temp file beside the target, then move -- a crash never leaves a half jar. */
    static void extract(Path target, Supplier<InputStream> baseline) throws IOException {
        Files.createDirectories(target.getParent());
        // the tmp carries our pid, NOT a shared name: slot maintenance runs before any InstanceLock exists,
        // so a double-launch has two processes in here at once, and one shared <target>.tmp let A's move
        // promote B's half-written bytes into the slot (EngineGuard.copyThroughTmp, same rule)
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
                Files.deleteIfExists(tmp); // ours alone now, so nothing else ever recycles a leftover
            } catch (IOException ignored) {
            }
            throw disk;
        }
        // the baseline is the shim's own twin, so it is known good by shipping and never inherits the verdict
        // that was hanging over the jar it just replaced
        EngineGuard.disarm(target);
    }

    /** The move that makes "atomic" TRUE on Windows: the plain replace there is delete-then-move, so a
     *  crash between the two syscalls leaves NO jar in the slot. Every caller's tmp is a resolveSibling,
     *  same volume, so the fallback only ever fires on a filesystem with no atomic rename at all, and
     *  there the two-step beats refusing to write. */
    static void replaceAtomic(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException odd) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The shim jar's own embedded baseline (packed by the build, see build.gradle). */
    public static Supplier<InputStream> embeddedBaseline() {
        return () -> EngineLoader.class.getResourceAsStream("/baseline/bamfos-updater-engine.jar");
    }

    private EngineLoader() {
    }
}
