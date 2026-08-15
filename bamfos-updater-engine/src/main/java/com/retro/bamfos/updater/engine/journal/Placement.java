package com.retro.bamfos.updater.engine.journal;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

// Replaces occupied target files safely on Windows, even when another process has the old file open (respects read-only files).
final class Placement {

    /** Which file-replacement strategy succeeded. */
    enum Rung {
        /** Direct atomic overwrite (target wasn't locked). */
        SWAP,
        /** Target was locked, so we renamed it out of the way and moved the new file in. */
        ASIDE
    }

    /** Temporary backup suffix for the existing file during a swap (separate from player-facing .bak-<version>
     *  backups). Deleted right after the new file moves into place; if left behind, a crash happened mid-swap.
     *  Doesn't end in .jar so FML mod scanning and duplicate-mod checks ignore it. */
    private static final String SUPERSEDED = ".superseded";

    /**
     * Replaces existing `to` with the contents of `from` and returns which strategy succeeded. Copies
     * beside `to` first so only a rename touches the target, leaving it intact if anything fails.
     */
    static Rung place(Path from, Path to) throws IOException {
        Path fresh = to.resolveSibling(to.getFileName() + ".new");
        try {
            Files.copy(from, fresh, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(fresh, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return Rung.SWAP;
            } catch (AtomicMoveNotSupportedException odd) {
                // Same-directory sibling, so atomic move should work; fall back to a plain move on filesystems
                // that don't support ATOMIC_MOVE rather than deferring for three boots.
                Files.move(fresh, to, StandardCopyOption.REPLACE_EXISTING);
                return Rung.SWAP;
            } catch (IOException held) {
                if (!Files.isWritable(to)) {
                    // Respect a read-only target as a player lock and defer instead of renaming it aside. Windows-only
                    // in practice: POSIX rename(2) already replaces chmod-444 targets in the atomic move above.
                    throw held;
                }
                if (!Files.isRegularFile(to)) {
                    // Only rename regular files aside; defer if a directory sits at the target path (tests also use
                    // non-empty directories as a portable stand-in for a locked file).
                    throw held;
                }
                return aside(fresh, to, held);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(fresh);
            } catch (IOException litter) {
                // Don't let cleanup errors hide the original failure.
            }
            throw e;
        }
    }

    /** Fallback path: rename the locked target aside, then move the new file in (restoring on failure). */
    private static Rung aside(Path fresh, Path to, IOException swapRefused) throws IOException {
        Path superseded = freeAsideName(to);
        try {
            Files.move(to, superseded);
        } catch (IOException cannotMoveAside) {
            // File isn't opened with delete-sharing, so neither replacement path can move it. Nothing changed on disk.
            cannotMoveAside.addSuppressed(swapRefused);
            throw cannotMoveAside;
        }
        try {
            Files.move(fresh, to); // Target path is free now, so we don't need REPLACE_EXISTING.
        } catch (IOException nameNowEmpty) {
            try {
                Files.move(superseded, to); // put it BACK, because an empty name breaks the boot outright
            } catch (IOException unrecoverable) {
                nameNowEmpty.addSuppressed(unrecoverable);
            }
            nameNowEmpty.addSuppressed(swapRefused);
            throw nameNowEmpty;
        }
        try {
            Files.delete(superseded);
        } catch (IOException litter) {
            // The new file is already in place, so don't fail the op if deleting the backup fails (Windows usually
            // allows delete even with an open handle, so this is rare).
        }
        return Rung.ASIDE;
    }

    /**
     * Picks a free aside name by numbering past leftovers instead of deleting them: a leftover usually
     * means something still locks it, so deleting would wedge retries forever. Bounded so chronic locks defer.
     */
    private static Path freeAsideName(Path to) throws IOException {
        Path plain = to.resolveSibling(to.getFileName() + SUPERSEDED);
        if (!Files.exists(plain)) {
            return plain;
        }
        for (int n = 1; n < 10; n++) {
            Path numbered = to.resolveSibling(to.getFileName() + SUPERSEDED + "." + n);
            if (!Files.exists(numbered)) {
                return numbered;
            }
        }
        throw new IOException("ten stranded " + SUPERSEDED + " copies beside " + to.getFileName()
                + ", refusing to add another");
    }

    private Placement() {
    }
}
