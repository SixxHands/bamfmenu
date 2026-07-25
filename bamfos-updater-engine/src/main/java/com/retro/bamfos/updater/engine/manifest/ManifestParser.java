package com.retro.bamfos.updater.engine.manifest;

import com.retro.bamfos.updater.engine.fs.PathGuard;
import com.retro.bamfos.updater.engine.json.MiniJson;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

// Parses and validates manifest.json, naming the exact field on error so a broken manifest is easy to spot from one log line.
public final class ManifestParser {

    /** Highest manifest schema this engine build understands. */
    public static final long KNOWN_SCHEMA = 1;

    public static class ManifestError extends RuntimeException {
        public ManifestError(String message) {
            super(message);
        }
    }

    public static final class SchemaTooNew extends ManifestError {
        public final long schemaVersion;

        public SchemaTooNew(long v) {
            super("manifest schema_version " + v + " is newer than this engine understands (" + KNOWN_SCHEMA + ") - update the updater");
            this.schemaVersion = v;
        }
    }

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern SHA512 = Pattern.compile("^[0-9a-f]{128}$");

    public static Manifest parse(String json) {
        Object root;
        try {
            root = MiniJson.parse(json);
        } catch (MiniJson.JsonError e) {
            throw new ManifestError("manifest is not valid JSON: " + e.getMessage());
        }
        Map<String, Object> m = obj(root, "manifest");

        long schema = reqLong(m, "schema_version", "manifest");
        if (schema > KNOWN_SCHEMA) {
            throw new SchemaTooNew(schema);
        }
        if (schema < 1) {
            throw new ManifestError("schema_version: must be >= 1, got " + schema);
        }

        Map<String, Object> packM = obj(req(m, "pack", "manifest"), "pack");
        Manifest.Pack pack = new Manifest.Pack(
                reqStr(packM, "id", "pack"),
                reqStr(packM, "version", "pack"),
                reqStr(packM, "mc", "pack"),
                reqStr(packM, "neoforge", "pack"),
                reqLong(packM, "updater_abi", "pack"),
                optLongList(packM, "engine_schema_support", "pack"));
        if (pack.updaterAbi() < 1) {
            throw new ManifestError("pack.updater_abi: must be >= 1, got " + pack.updaterAbi());
        }

        Object rr = req(m, "requires_relaunch", "manifest");
        if (!(rr instanceof Boolean b)) {
            throw new ManifestError("requires_relaunch: expected true/false");
        }

        List<String> removed = optStrList(m, "removed_modids", "manifest");

        Object filesRaw = req(m, "files", "manifest");
        if (!(filesRaw instanceof List<?> filesList)) {
            throw new ManifestError("files: expected an array");
        }
        List<FileEntry> files = new ArrayList<>(filesList.size());
        /* Case-insensitive duplicate detection, but SIDE-AWARE: the same path on disjoint sides is
           the DESIGNED variant mechanism (the ModernFix client/server config pair, server-linked
           optionals), only entries whose side reach intersects fight over one Windows file. */
        Map<String, List<int[]>> seen = new HashMap<>(); // caseKey -> [(index, reachBits)]
        for (int i = 0; i < filesList.size(); i++) {
            String at = "files[" + i + "]";
            FileEntry e = parseEntry(obj(filesList.get(i), at), at);
            int reach = switch (e.side()) {
                case BOTH -> 0b11;
                case CLIENT -> 0b01;
                case SERVER -> 0b10;
            };
            List<int[]> prior = seen.computeIfAbsent(PathGuard.caseKey(e.path()), k -> new ArrayList<>());
            for (int[] p : prior) {
                if ((p[1] & reach) != 0) {
                    throw new ManifestError(at + ".path: '" + e.path() + "' collides case-insensitively on side '"
                            + e.side() + "' with files[" + p[0] + "]");
                }
            }
            prior.add(new int[]{i, reach});
            files.add(e);
        }

        return new Manifest(schema, pack, b, List.copyOf(removed), List.copyOf(files));
    }

    private static FileEntry parseEntry(Map<String, Object> e, String at) {
        String path = reqStr(e, "path", at);
        try {
            PathGuard.validate(path);
        } catch (PathGuard.BadPath bp) {
            throw new ManifestError(at + ".path: " + bp.getMessage());
        }

        String sha = reqStr(e, "sha256", at);
        if (!SHA256.matcher(sha).matches()) {
            throw new ManifestError(at + ".sha256: expected 64 lowercase hex chars, got '" + sha + "'");
        }
        long size = reqLong(e, "size", at);
        if (size < 0) {
            throw new ManifestError(at + ".size: negative");
        }

        FileEntry.Side side = enumOf(FileEntry.Side.class, reqStr(e, "side", at), at + ".side");
        FileEntry.Policy policy = switch (reqStr(e, "policy", at)) {
            case "managed" -> FileEntry.Policy.MANAGED;
            case "seed-once" -> FileEntry.Policy.SEED_ONCE;
            default -> throw new ManifestError(at + ".policy: expected 'managed' or 'seed-once'");
        };
        FileEntry.Clazz clazz = e.containsKey("class")
                ? enumOf(FileEntry.Clazz.class, str(e.get("class"), at + ".class"), at + ".class")
                : FileEntry.Clazz.HOT;

        FileEntry.Source source = parseSource(obj(req(e, "source", at), at + ".source"), at + ".source");

        List<String> modids = optStrList(e, "modids", at);
        boolean installDisabled = false;
        if (e.containsKey("install_state")) {
            String st = str(e.get("install_state"), at + ".install_state");
            if (!st.equals("disabled")) {
                throw new ManifestError(at + ".install_state: only 'disabled' is defined, got '" + st + "'");
            }
            installDisabled = true;
        }
        String category = e.containsKey("category") ? str(e.get("category"), at + ".category") : null;
        FileEntry.OnRemove onRemove = FileEntry.OnRemove.DELETE;
        if (e.containsKey("on_remove")) {
            onRemove = enumOf(FileEntry.OnRemove.class, str(e.get("on_remove"), at + ".on_remove"), at + ".on_remove");
        }

        // Ignore unknown merge values so older engines safely fall back to the base policy.
        String merge = e.get("merge") instanceof String mg && mg.equals("servers") ? mg : null;

        return new FileEntry(path, sha, size, side, policy, clazz, source, List.copyOf(modids), installDisabled, category, onRemove, merge);
    }

    private static FileEntry.Source parseSource(Map<String, Object> s, String at) {
        String type = reqStr(s, "type", at);
        switch (type) {
            case "release-zip" -> {
                String inner = reqStr(s, "inner", at);
                try {
                    PathGuard.validate(inner);
                } catch (PathGuard.BadPath bp) {
                    throw new ManifestError(at + ".inner: " + bp.getMessage());
                }
                return new FileEntry.Source(FileEntry.Source.Type.RELEASE_ZIP,
                        reqStr(s, "asset", at), inner, null, null, null);
            }
            case "release-asset" -> {
                String release = s.containsKey("release") ? str(s.get("release"), at + ".release") : null;
                return new FileEntry.Source(FileEntry.Source.Type.RELEASE_ASSET,
                        reqStr(s, "asset", at), null, release, null, null);
            }
            case "url" -> {
                String sha512 = null;
                if (s.containsKey("sha512")) {
                    sha512 = str(s.get("sha512"), at + ".sha512");
                    if (!SHA512.matcher(sha512).matches()) {
                        throw new ManifestError(at + ".sha512: expected 128 lowercase hex chars");
                    }
                }
                return new FileEntry.Source(FileEntry.Source.Type.URL,
                        null, null, null, reqStr(s, "url", at), sha512);
            }
            default -> throw new ManifestError(at + ".type: unknown source type '" + type + "'");
        }
    }

    // Typed field accessors that include the field name in errors.

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object v, String at) {
        if (!(v instanceof Map)) {
            throw new ManifestError(at + ": expected an object");
        }
        return (Map<String, Object>) v;
    }

    private static Object req(Map<String, Object> m, String key, String at) {
        Object v = m.get(key);
        if (v == null) {
            throw new ManifestError(at + "." + key + ": missing");
        }
        return v;
    }

    private static String reqStr(Map<String, Object> m, String key, String at) {
        return str(req(m, key, at), at + "." + key);
    }

    private static String str(Object v, String at) {
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new ManifestError(at + ": expected a non-empty string");
        }
        return s;
    }

    private static long reqLong(Map<String, Object> m, String key, String at) {
        Object v = req(m, key, at);
        if (!(v instanceof Long l)) {
            throw new ManifestError(at + "." + key + ": expected an integer");
        }
        return l;
    }

    private static List<String> optStrList(Map<String, Object> m, String key, String at) {
        Object v = m.get(key);
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> list)) {
            throw new ManifestError(at + "." + key + ": expected an array");
        }
        List<String> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            out.add(str(list.get(i), at + "." + key + "[" + i + "]"));
        }
        return out;
    }

    private static List<Long> optLongList(Map<String, Object> m, String key, String at) {
        Object v = m.get(key);
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> list)) {
            throw new ManifestError(at + "." + key + ": expected an array");
        }
        List<Long> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object x = list.get(i);
            if (!(x instanceof Long l)) {
                throw new ManifestError(at + "." + key + "[" + i + "]: expected an integer");
            }
            out.add(l);
        }
        return out;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String raw, String at) {
        try {
            return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            throw new ManifestError(at + ": unknown value '" + raw + "'");
        }
    }

    private ManifestParser() {
    }
}
