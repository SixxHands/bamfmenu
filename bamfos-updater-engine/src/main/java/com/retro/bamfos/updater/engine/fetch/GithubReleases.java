package com.retro.bamfos.updater.engine.fetch;

import com.retro.bamfos.updater.engine.json.MiniJson;
import com.retro.bamfos.updater.engine.manifest.FileEntry;
import com.retro.bamfos.updater.engine.reconcile.Reconciler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
        return "https://api.github.com";
    }

    /** The release JSON for a tag ("latest" = the /releases/latest endpoint, server door only). */
    public Map<?, ?> release(String tag) throws IOException, InterruptedException {
        String url = baseUrl + "/repos/" + repo + "/releases/"
                + ("latest".equals(tag) ? "latest" : "tags/" + tag);
        try (InputStream in = http.get(url, token, "application/vnd.github+json").body()) {
            Object parsed = MiniJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> m)) {
                throw new IOException("release response is not an object");
            }
            Map<String, Long> ids = new HashMap<>();
            if (m.get("assets") instanceof List<?> assets) {
                for (Object a : assets) {
                    if (a instanceof Map<?, ?> am && am.get("name") instanceof String n && am.get("id") instanceof Long id) {
                        ids.put(n, id);
                        // GITHUB SANITIZES asset filenames on upload (spaces become dots, parens
                        // vanish): the manifest says "ATi Structures (1.21+).jar", the release says
                        // "ATi.Structures.1.21+.jar", and an exact-match lookup strands every such
                        // mod. Key a normalized form too; a wrong pick can't slip through because the
                        // hash check downstream rejects it loudly.
                        ids.putIfAbsent(norm(n), id);
                    }
                }
            }
            assetIds.put(String.valueOf(m.get("tag_name")), ids);
            if (m.get("tag_name") instanceof String tn && !tn.equals(tag) && !"latest".equals(tag)) {
                throw new IOException("asked for tag " + tag + ", got " + tn);
            }
            if (tag.equals(this.tag)) {
                targetRelease = m;
            }
            return m;
        }
    }

    /** Returns a string field from the release JSON (e.g. "body" for release notes), or null if absent. */
    public String targetReleaseField(String key) {
        return targetRelease != null && targetRelease.get(key) instanceof String s ? s : null;
    }

    public String manifestJson() throws IOException, InterruptedException {
        release(tag);
        Path tmp = cacheDir.resolve("manifest-" + tag + ".json");
        downloadAsset(tag, "manifest.json", tmp);
        return java.nio.file.Files.readString(tmp, StandardCharsets.UTF_8);
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
