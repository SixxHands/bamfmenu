package com.retro.bamfos.updater.engine.guard;

import com.retro.bamfos.updater.engine.fetch.HttpFetcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// Engine side of boot-once verification: deletes the probation flag once the engine completes a working sync step.
public final class EngineHealth {

    /** Probation flag shared with EngineGuard via the filesystem (isolated classloaders prevent linking).
     *  Keep this filename identical in both modules or newly promoted engines will always roll back. */
    private static final String PROBATION = ".bamfos-updater/engine/engine-probation";

    /** Refusal and strike files shared with EngineGuard by filename. The engine only deletes these
     *  during an explicit operator repair; never clear them automatically. */
    private static final String REJECTED = ".bamfos-updater/engine/engine-rejected";
    private static final String STRIKES = ".bamfos-updater/engine/engine-strikes";

    /** Clears probation: this run reached a state it can account for. Never throws. */
    public static void proven(Path dir) {
        try {
            Files.deleteIfExists(dir.resolve(PROBATION));
        } catch (IOException | RuntimeException held) {
            // If we can't delete the flag, ignore it. Worst case it triggers an extra rollback next boot,
            // which will log it then.
        }
    }

    /** Marks the engine healthy and returns the given exit result in one call. */
    public static int proven(Path dir, int code) {
        proven(dir);
        return code;
    }

    /** Clears refused engine shas and strikes on manual repair only (BIOS repair, server repair) so a
     *  false-positive refusal can be recovered; truly bad engines re-earn refusal in two boots. Never throws. */
    public static void forgiveRejectedEngines(Path dir) {
        try {
            Files.deleteIfExists(dir.resolve(REJECTED));
            Files.deleteIfExists(dir.resolve(STRIKES));
        } catch (IOException | RuntimeException held) {
            // Don't fail boot if we can't clear the rejected-engine record.
        }
    }

    /** Reads the rejected engine SHA list so sync doesn't re-download jars the shim already blocked.
     *  Uses the first tab-separated field per line (the shim also writes a timestamp column). */
    public static List<String> rejectedEngines(Path dir) {
        try {
            Path f = dir.resolve(REJECTED);
            if (!Files.isRegularFile(f)) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String sha = line.split("\t")[0].trim();
                if (!sha.isEmpty()) {
                    out.add(sha);
                }
            }
            return out;
        } catch (IOException | RuntimeException unreadable) {
            return List.of(); // If the refusal file is unreadable, fall back to re-downloading rather than failing boot.
        }
    }

    /** Returns true for external faults (TransportFault, java.net/ssl causes, rate limits, bad token, HTTP
     *  status) so they still clear probation; false for internal engine errors (like missing manifest assets).
     *  Unlike SyncCore.networkFault, bad config URLs count as external. Cause walk is bounded against cycles. */
    public static boolean attributable(Throwable t) {
        Throwable c = t;
        for (int depth = 0; c != null && depth < 8; depth++) {
            if (c instanceof HttpFetcher.TokenDead || c instanceof HttpFetcher.RateLimited
                    || c instanceof HttpFetcher.HttpStatusError || c instanceof HttpFetcher.TransportFault) {
                return true;
            }
            String pkg = c.getClass().getPackageName();
            if (pkg.equals("java.net") || pkg.equals("java.net.http") || pkg.equals("javax.net.ssl")) {
                return true;
            }
            c = c.getCause();
        }
        return false;
    }

    private EngineHealth() {
    }
}
