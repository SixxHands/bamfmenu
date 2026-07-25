package com.retro.bamfos.updater.engine;

import com.retro.bamfos.updater.engine.door.ClientDoor;
import com.retro.bamfos.updater.engine.door.ServerDoor;
import com.retro.bamfos.updater.engine.fetch.GithubReleases;

import java.nio.file.Path;
import java.nio.file.Paths;

// Reflective entry point called by the core across an isolated classloader. Do NOT rename this class or enter(String[]).
public final class EngineEntry {

    /** Engine build version stamped by gradle into the jar manifest for logging. */
    public static String version() {
        String v = EngineEntry.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    /**
     * Runs the engine. args carry the door's context: --server --dir D [--tag T] [--repair].
     * Return code is the shim's exit code on the server door -- 0 always except corrupted-local-state
     *.
     */
    public static int enter(String[] args) {
        System.out.println("[updater] engine " + version() + " up");
        boolean server = false;
        boolean client = false;
        boolean repair = false;
        String tag = "";
        Path dir = Paths.get(".");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--server" -> server = true;
                case "--client" -> client = true;
                case "--repair" -> repair = true;
                case "--tag" -> tag = i + 1 < args.length ? args[++i] : "";
                case "--dir" -> dir = i + 1 < args.length ? Paths.get(args[++i]) : dir;
                default -> {
                }
            }
        }
        if (server) {
            return new ServerDoor(GithubReleases.realApi()).run(dir, tag, repair);
        }
        if (client) {
            // bind ops before running the door so the BIOS menu still works if boot sync fails
            com.retro.bamfos.updater.engine.ops.OpsGateway.bind(dir, GithubReleases.realApi());
            return new ClientDoor(GithubReleases.realApi()).run(dir);
        }
        return 0;
    }

    private EngineEntry() {
    }
}
