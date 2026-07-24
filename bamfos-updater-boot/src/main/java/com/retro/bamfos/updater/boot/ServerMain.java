package com.retro.bamfos.updater.boot;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The server door: java -jar bamfos-updater-boot.jar --server --dir . [--tag v] [--repair]
 * Runs before the server JVM, so nothing is locked and no locator is involved. rules the exit
 * code: 0 on ANY failure after logging one line -- the wrapper must always proceed to launch the
 * server; non-zero is reserved for corrupted-local-state cases a human must see (engine decides).
 */
public final class ServerMain {

    public static void main(String[] args) {
        try {
            Path dir = Paths.get(".");
            for (int i = 0; i < args.length - 1; i++) {
                if (args[i].equals("--dir")) {
                    dir = Paths.get(args[i + 1]);
                }
            }
            Path engineJar = dir.resolve(".bamfos-updater").resolve("engine").resolve("bamfos-updater-engine.jar");
            EngineLoader.Engine engine = EngineLoader.ensureAndLoad(engineJar, EngineLoader.embeddedBaseline());
            System.exit(engine.enter(args));
        } catch (Throwable t) {
            System.err.println("[updater] sync skipped: " + t);
            System.exit(0); // the updater must never be the reason the server won't boot
        }
    }

    private ServerMain() {
    }
}
