package com.retro.bamfos.updater.engine.manifest;

import java.util.List;

// Parsed immutable release manifest. Unknown JSON fields are ignored; version strings are opaque.
public record Manifest(
        long schemaVersion,
        Pack pack,
        boolean requiresRelaunch,
        List<String> removedModids,
        List<FileEntry> files) {

    public record Pack(
            String id,
            String version,
            String mc,
            String neoforge,
            long updaterAbi,
            List<Long> engineSchemaSupport) {
    }
}
