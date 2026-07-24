package com.retro.bamfos.updater.engine.manifest;

import java.util.List;

// One managed file entry (PathGuard-validated path; sha256 is the change detector, size is for progress bars).
public record FileEntry(
        String path,
        String sha256,
        long size,
        Side side,
        Policy policy,
        Clazz clazz,
        Source source,
        List<String> modids,
        boolean installDisabled,
        String category,
        OnRemove onRemove) {

    public enum Side { BOTH, CLIENT, SERVER }

    public enum Policy { MANAGED, SEED_ONCE }

    /** hot = applies in a normal sync; relaunch = the per-file escape hatch. */
    public enum Clazz { HOT, RELAUNCH }

    /** What this rule does when this entry later vanishes from the manifest. */
    public enum OnRemove { DELETE, ORPHAN }

    /**
     * Byte source shape by type: release-zip (asset + inner), release-asset (asset + optional older
     * release backref, null means current release), or url (defined, unused by policy).
     */
    public record Source(Type type, String asset, String inner, String release, String url, String sha512) {
        public enum Type { RELEASE_ZIP, RELEASE_ASSET, URL }
    }
}
