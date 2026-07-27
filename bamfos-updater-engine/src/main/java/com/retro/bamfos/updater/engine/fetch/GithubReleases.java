package com.retro.bamfos.updater.engine.fetch;

import com.retro.bamfos.updater.engine.json.MiniJson;
import com.retro.bamfos.updater.engine.manifest.FileEntry;
import com.retro.bamfos.updater.engine.reconcile.Reconciler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

// Resolves GitHub releases and fetches release assets (caching release zips locally per sync).
public final class GithubReleases implements Reconciler.Fetcher {

    private final HttpFetcher http = new HttpFetcher();
    private final String baseUrl;
    private final String repo;
    private final String token;
    private final String tag;
    private final Path cacheDir;
    private final Map<String, Map<String, Long>> assetIds = new HashMap<>(); // tag -> name -> id
    private final Map<String, Path> zipCache = new HashMap<>();
    private Map<?, ?> targetRelease; // the release JSON of THE tag: notes body etc, free

    public GithubReleases(String baseUrl, String repo, String token, String tag, Path cacheDir) {
        this.baseUrl = baseUrl;
        this.repo = repo;
        this.token = token;
        this.tag = tag;
        this.cacheDir = cacheDir;
    }

    public static String realApi() {
        // Test/integration seam: honored ONLY when the env var is set (never in a shipped run, so
        // production behavior is byte-identical), letting a local fixture stand in for GitHub for a
        // REAL boot.jar --server end-to-end. Same intent as ServerDoor's injectable apiBase, but
        // reachable from the shim's reflected entry (EngineEntry) which always calls realApi().
        String override = System.getenv("BAMFOS_API_BASE");
        return override != null && !override.isBlank() ? override : "https://api.github.com";
    }

    // Tag 404s immediately instead of retrying, since only publishing the release fixes a missing tag.
    public static final class ReleaseMissing extends HttpFetcher.HttpStatusError {
        public final String repo;
        public final String tag;
        public final boolean alias;

        public ReleaseMissing(String repo, String tag, String url) {
            super(404, url);
            this.repo = repo;
            this.tag = tag;
            this.alias = "latest".equals(tag);
        }
    }

    /** The release JSON for a tag ("latest" = the /releases/latest endpoint, server door only). */
    public Map<?, ?> release(String tag) throws IOException, InterruptedException {
        String url = baseUrl + "/repos/" + repo + "/releases/"
                + ("latest".equals(tag) ? "latest" : "tags/" + tag);
        byte[] json;
        try {
            // use getBody instead of readAllBytes: HttpRequest.timeout only covers response headers, so a server
            // that stalls mid-body would hang forever (blocking FML discovery on the client)
            json = http.getBody(url, token, "application/vnd.github+json");
        } catch (HttpFetcher.HttpStatusError status) {
            // Throw ReleaseMissing here while we still know the requested tag, and only for the target tag
            // (a 404 on a backref release is a broken entry source, not a missing release channel).
            throw status.code == 404 && tag.equals(this.tag) ? new ReleaseMissing(repo, tag, url) : status;
        }
        Object parsed = MiniJson.parse(new String(json, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> m)) {
            throw new IOException("release response is not an object");
        }
        Map<String, Long> ids = new HashMap<>();
        if (m.get("assets") instanceof List<?> assets) {
            for (Object a : assets) {
                if (a instanceof Map<?, ?> am && am.get("name") instanceof String n && am.get("id") instanceof Long id) {
                    ids.put(n, id);
                    // GitHub sanitizes uploaded asset names (spaces -> dots, parens stripped), so index a
                    // normalized key too; downstream hash verification still rejects any wrong match.
                    ids.putIfAbsent(norm(n), id);
                }
            }
        }
        assetIds.put(String.valueOf(m.get("tag_name")), ids);
        // Also cache under the requested tag alias (like "latest") so follow-up downloadAsset("latest", ...)
        // lookups hit instead of throwing and leaving the server stuck on an old version.
        assetIds.put(tag, ids);
        if (m.get("tag_name") instanceof String tn && !tn.equals(tag) && !"latest".equals(tag)) {
            throw new IOException("asked for tag " + tag + ", got " + tn);
        }
        if (tag.equals(this.tag)) {
            targetRelease = m;
        }
        return m;
    }

    /** Fetches non-draft releases newest-first for the rollback picker via HttpFetcher (preserving
     *  RateLimited/TokenDead). Caps per_page on the wire so huge asset arrays don't exceed getBody's
     *  8 MB limit, and filters out drafts since /releases/tags/&lt;tag&gt; cannot resolve them. */
    public List<Map<?, ?>> releases(int max) throws IOException, InterruptedException {
        Object parsed = MiniJson.parse(new String(
                http.getBody(baseUrl + "/repos/" + repo + "/releases?per_page=" + max, token,
                        "application/vnd.github+json"), StandardCharsets.UTF_8));
        if (!(parsed instanceof List<?> arr)) {
            throw new IOException("releases response is not an array");
        }
        List<Map<?, ?>> out = new ArrayList<>();
        for (Object o : arr) {
            if (o instanceof Map<?, ?> m && !Boolean.TRUE.equals(m.get("draft"))) {
                out.add(m);
            }
            if (out.size() == max) {
                break; // cap results in case the server ignores per_page
            }
        }
        return out;
    }

    /** Returns a string field from the release JSON (e.g. "body" for release notes), or null if absent. */
    public String targetReleaseField(String key) {
        return targetRelease != null && targetRelease.get(key) instanceof String s ? s : null;
    }

    public String manifestJson() throws IOException, InterruptedException {
        release(tag);
        pruneCaches();
        Path tmp = cacheDir.resolve("manifest-" + tag + ".json");
        downloadAsset(tag, "manifest.json", tmp);
        return java.nio.file.Files.readString(tmp, StandardCharsets.UTF_8);
    }

    /**
     * The release caches from every PREVIOUS sync, swept. Both names here are tag-unique and nothing ever
     * removed either, so the real pack (which sources the whole config bucket out of one release zip) left a
     * fresh copy of that zip in staging per release: twenty releases, twenty copies plus twenty manifest
     * jsons, hundreds of MB inside the instance folder that no UI names and the player cannot know is safe to
     * delete. Free to drop, because these files are only ever re-read through the in-memory zipCache, which lives
     * exactly one sync, so a file from an older run is already dead weight rather than a saved download.
     * Runs from manifestJson, which is the first thing every sync does, so nothing this sync wrote can be in
     * the sweep. Staged .part files are NOT touched: those are 's bytes and only the Reconciler knows
     * when a journal is done with them.
     */
    private void pruneCaches() {
        try (java.nio.file.DirectoryStream<Path> ds = java.nio.file.Files.newDirectoryStream(cacheDir)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (n.startsWith("zip-") || (n.startsWith("manifest-") && n.endsWith(".json"))) {
                    java.nio.file.Files.deleteIfExists(p);
                }
            }
        } catch (IOException litter) {
            // housekeeping, never a reason to fail a sync
        }
    }

    @Override
    public void fetch(FileEntry entry, Path dest) throws IOException {
        try {
            FileEntry.Source s = entry.source();
            String from = s.release() != null ? s.release() : tag;
            switch (s.type()) {
                case RELEASE_ASSET -> downloadAsset(from, s.asset(), dest);
                case RELEASE_ZIP -> extractInner(from, s.asset(), s.inner(), dest);
                case URL -> throw new IOException("url sources are unused by policy (SPEC 4.1)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private void downloadAsset(String fromTag, String name, Path dest) throws IOException, InterruptedException {
        Map<String, Long> ids = assetIds.get(fromTag);
        if (ids == null) {
            release(fromTag);
            ids = assetIds.get(fromTag);
        }
        Long id = ids == null ? null : ids.get(name);
        if (id == null && ids != null) {
            id = ids.get(norm(name)); // the sanitized-name fallback, see release()
        }
        if (id == null) {
            throw new IOException("asset '" + name + "' not on release " + fromTag + " (load-bearing release deleted?)");
        }
        // this endpoint redirects (302) to S3; HttpFetcher strips the auth header on redirect
        http.download(baseUrl + "/repos/" + repo + "/releases/assets/" + id,
                token, "application/octet-stream", dest);
    }

    /** Normalizes a name to lowercase alphanumerics so matching survives filename sanitization. */
    private static String norm(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private void extractInner(String fromTag, String zipAsset, String inner, Path dest) throws IOException, InterruptedException {
        String key = fromTag + "/" + zipAsset;
        Path zip = zipCache.get(key);
        if (zip == null) {
            zip = cacheDir.resolve("zip-" + fromTag + "-" + zipAsset);
            downloadAsset(fromTag, zipAsset, zip);
            zipCache.put(key, zip);
        }
        try (ZipInputStream zin = new ZipInputStream(java.nio.file.Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.getName().equals(inner)) {
                    java.nio.file.Files.createDirectories(dest.getParent());
                    java.nio.file.Files.copy(zin, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
            }
        }
        throw new IOException("inner '" + inner + "' missing from " + zipAsset + " on " + fromTag);
    }
}
