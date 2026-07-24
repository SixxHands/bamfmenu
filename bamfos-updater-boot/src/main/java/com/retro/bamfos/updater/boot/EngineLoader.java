package com.retro.bamfos.updater.boot;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
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
 * back to the embedded baseline once, then loads that.
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
        // the engine cannot replace itself while loaded, so a sync stages engine.jar.new and WE
        // swap it here, before anything has the file open -- same contract as the wrapper's shim
        // swap line, one pattern all the way down
        Path staged = enginePath.resolveSibling(enginePath.getFileName() + ".new");
        if (Files.isRegularFile(staged)) {
            Files.createDirectories(enginePath.getParent());
            Files.move(staged, enginePath, StandardCopyOption.REPLACE_EXISTING);
        }
        if (!Files.isRegularFile(enginePath)) {
            extract(enginePath, baseline);
        }
        try {
            return load(enginePath);
        } catch (ReflectiveOperationException | IOException | LinkageError first) {
            // corrupt on-disk engine: re-extract the baseline once and retry; a second failure is real
            extract(enginePath, baseline);
            try {
                return load(enginePath);
            } catch (ReflectiveOperationException | LinkageError second) {
                throw new IOException("baseline engine failed to load", second);
            }
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
        } catch (ReflectiveOperationException e) {
            loader.close();
            throw e;
        }
    }

    /** Atomic extract: temp file beside the target, then move -- a crash never leaves a half jar. */
    private static void extract(Path target, Supplier<InputStream> baseline) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (InputStream in = baseline.get()) {
            if (in == null) {
                throw new IOException("embedded baseline engine resource is missing from the shim jar");
            }
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** The shim jar's own embedded baseline (packed by the build, see build.gradle). */
    public static Supplier<InputStream> embeddedBaseline() {
        return () -> EngineLoader.class.getResourceAsStream("/baseline/bamfos-updater-engine.jar");
    }

    private EngineLoader() {
    }
}
