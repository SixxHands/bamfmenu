package com.retro.bamfos.updater.engine.factory;

import com.retro.bamfos.updater.engine.reconcile.Reconciler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

// Builds the exact include list of files a factory reset wipes (intentionally an allowlist rather than a blocklist so unknown player folders can't get wiped by accident).
public final class WipeSet {

    /** Updater files that a reset must never delete: the shim and engine are locked while running, and
     *  the config/state files record which server this install belongs to. */
    public static final Set<String> KEEPERS = Set.of(
            "bamfos-updater-boot.jar",                                  // server: the shim, at the root
            "mods/bamfos-updater-boot.jar",                             // client: the shim, an FML locator
            "mods/bamfos-updater-boot.jar.new",                         // a staged shim waiting to be promoted
            ".bamfos-updater/engine/bamfos-updater-engine.jar",         // held open by this very JVM
            ".bamfos-updater/bamfos-updater.properties",                // server_host, port, repo
            "servers.dat");                                             // the player's whole server list

    /** Directories a factory reset must never enter (asserted explicitly): user worlds, screenshots,
     *  logs for debugging, and resource/shader packs (managed packs are reconciled by normal sync). */
    public static final Set<String> PROTECTED_ROOTS = Set.of(
            "saves", "screenshots", "logs", "crash-reports", "resourcepacks", "shaderpacks",
            "defaultconfigs", "journeymap", "local", "libraries", "versions", "backups");

    /** Player settings files in the root directory that factory reset leaves untouched. */
    private static final Set<String> PROTECTED_FILES = Set.of("optionsof.txt", "optionsshaders.txt");

    /** Cache files deleted on reset so the next sync rebuilds them cleanly instead of reading stale state. */
    private static final Set<String> STATE_FILES = Set.of(
            "manifest-current.json", "last-sync.json", "release-notes.json", "bus.log", "token-dead",
            // delete the ledger too so the next sync re-hashes every file on disk to verify what actually landed
            "ledger.json");

    private WipeSet() {
    }

    /** Files the reset will delete, after checking none of them are protected. */
    public record Plan(List<String> delete, List<String> refused) {

        /** Thrown if the wipe set ever includes a protected keeper file; callers must abort before journaling. */
        public boolean safe() {
            return refused.isEmpty();
        }
    }

    /** Walks the reset-owned trees and returns instance-relative paths to delete.
     *  Both client and server shim jars are kept regardless of `side` so neither gets wiped by accident. */
    public static Plan enumerate(Path dir, Reconciler.Side side) throws IOException {
        List<String> delete = new ArrayList<>();

        // R1 + R3: wipe every regular file under these dirs recursively, including unmanaged leftovers like
        // configs the pack no longer ships
        walkInto(dir, "config", delete);
        walkInto(dir, "kubejs", delete);

        // Wipe jars anywhere under mods/ recursively, including .disabled files.
        walkJars(dir, delete);

        // R4: one way, nothing restores it. Minecraft writes a fresh vanilla one on the next run
        addIfFile(dir, "options.txt", delete);

        for (String name : STATE_FILES) {
            addIfFile(dir, ".bamfos-updater/" + name, delete);
        }
        walkInto(dir, ".bamfos-updater/staging", delete); // half-fetched parts from some earlier run

        List<String> refused = new ArrayList<>();
        for (String rel : delete) {
            if (KEEPERS.contains(rel) || protectedPath(rel)) {
                refused.add(rel);
            }
        }
        return new Plan(List.copyOf(delete), List.copyOf(refused));
    }

    /** Final check over the finished wipe list to guarantee no forbidden tree or keeper slips through,
     *  intentionally independent of the walk logic above. */
    static boolean protectedPath(String rel) {
        if (PROTECTED_FILES.contains(rel)) {
            return true;
        }
        int slash = rel.indexOf('/');
        String root = slash < 0 ? rel : rel.substring(0, slash);
        return PROTECTED_ROOTS.contains(root);
    }

    private static void addIfFile(Path dir, String rel, List<String> out) {
        if (Files.isRegularFile(dir.resolve(rel))) {
            out.add(rel);
        }
    }

    private static void walkInto(Path dir, String rel, List<String> out) throws IOException {
        Path root = dir.resolve(rel);
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(root)) {
            tree.filter(Files::isRegularFile).forEach(p -> out.add(rel(dir, p)));
        }
    }

    /** Only match jar files so we don't wipe screenshots or notes a player put in mods/. */
    private static void walkJars(Path dir, List<String> out) throws IOException {
        Path mods = dir.resolve("mods");
        if (!Files.isDirectory(mods)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(mods)) {
            tree.filter(Files::isRegularFile)
                    .map(p -> rel(dir, p))
                    .filter(r -> r.endsWith(".jar") || r.endsWith(".jar.disabled"))
                    .filter(r -> !KEEPERS.contains(r))
                    .forEach(out::add);
        }
    }

    /** Forward slashes always: a journal op is compared against manifest paths, which are never Windows. */
    private static String rel(Path dir, Path file) {
        return dir.relativize(file).toString().replace('\\', '/');
    }
}
