package com.retro.bamfmenu.early.core.song;

import com.retro.bamfmenu.early.core.asset.Art;
import com.retro.bamfmenu.early.core.json.Json;
import com.retro.bamfmenu.early.core.panel.FaceFrameAnimation;
import com.retro.bamfmenu.early.core.panel.LeftPanelAnimation;
import com.retro.bamfmenu.early.core.panel.LeftPanelTrack;
import com.retro.bamfmenu.early.core.rhythm.chart.Chart;
import com.retro.bamfmenu.early.core.rhythm.chart.ChartIO;
import com.retro.bamfmenu.early.core.special.PlaceholderSpecial;
import com.retro.bamfmenu.early.core.special.SpecialSession;
import com.retro.bamfmenu.early.core.tuning.DetectorConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/* data-driven discovery of the BUILT-IN songs. Each song is a /songs/<id>/song.json (folder name =
   id); discover() finds every such folder, parses its descriptor (via the dependency-free Json reader), and
   builds a SongDef -- so adding a song is "drop a folder with a song.json + an OGG", no Java edit.
   SongRegistry.SONGS is just this result.

   Enumeration is the hard part. The early window ships as a JPMS module loaded in an FML layer, so
   getResource("/songs") returns a `union:` URL (cpw's union filesystem), NOT file:/jar:, and
   ModuleReader.list() returns null there. What ALWAYS works is reading a KNOWN resource path
   (getResourceAsStream), and -- proven by probe -- deriving the containing jar's real filesystem path from the
   union/jar URL and scanning it as a ZipFile. So: file: (dev exploded dir) -> list the directory; everything
   else -> find the jar on disk and scan its entries. And if enumeration EVER yields nothing, a hardcoded
   built-in fallback (loaded by known path) guarantees SONGS is never empty -- the shipped songs always play,
   so SONGS[0] can never AIOOBE again.

   What song.json CAN'T carry is CODE: a reactive left panel and a SPECIAL session are suppliers, not data.
   Those live in BUILTINS, keyed by id, merged onto the discovered descriptor. A discovered song not in BUILTINS
   correctly gets a blank left cell and no special.

   Scope: this discovers songs bundled in the jar / dev resources only. Discovering songs from an external
   on-disk folder (<gameDir>/BAMFOS/songs/) and loading their audio off-disk is a deliberate (editor)
   feature. Pure stdlib + the core song/tuning/panel types; no GL/MC, safe pre-Minecraft. */
public final class SongLoader {

    private SongLoader() {
    }

    /* The anchor song the rest of the system assumes is SONGS[0] (the cutscene leads into it; "Enter Recovery
       Mode" plays it). discover() pins it to index 0 regardless of authored order. */
    private static final String RECOVERY_ID = "recovery";

    /* Last-resort ids if enumeration finds nothing (each still loaded from its bundled song.json by known
       path, which is reliable in every classloader). Keeps SONGS non-empty so the shipped songs always work. */
    private static final String[] FALLBACK_IDS = {"recovery", "far", "tetris"};

    /* Sentinel ogg value for a folder that holds more than one SONG OGG (intro.ogg excluded): the editor refuses
       to open it ("use only one song per chart"). Distinct from a real ref (classpath "/songs/..." or "file:..."). */
    public static final String MULTI_OGG = "multi:";

    /* A reserved secondary OGG name that is NOT the song (Recovery's cutscene jingle); excluded from the "one song
       per folder" count + never chosen as the primary audio. */
    private static final String INTRO_OGG = "intro.ogg";

    /* Songs that carry bespoke CODE (a reactive left panel and/or a SPECIAL session) which can't live in
       song.json. Keyed by folder id. The supplier shape keeps panel/special PERMISSIVE + pluggable exactly as
       before -- only their wiring moved here from SongRegistry. Null components are fine inside the record
       (Map.of just needs non-null Builtin VALUES). */
    private record Builtin(Supplier<LeftPanelTrack> panel, Supplier<SpecialSession> special) {
    }

    private static final Map<String, Builtin> BUILTINS = Map.of(
            RECOVERY_ID, new Builtin(SongLoader::recoveryFace, null),
            "far", new Builtin(null, PlaceholderSpecial::new)
    );

    /* The registry's song list: every discoverable song, sorted by authored order then id, with Recovery
       anchored to index 0. Best-effort and total -- a single bad descriptor drops only that song, and an empty
       enumeration falls back to the built-ins, so this never throws and never returns an empty array (unless
       even the bundled built-in descriptors are missing). */
    public static SongDef[] discover() {
        List<SongDef> out = new ArrayList<>();
        Set<String> ids = songIds();
        for (String id : ids) {
            SongDef sd = load(id);
            if (sd != null) {
                out.add(sd);
            }
        }
        if (out.isEmpty()) {
            // Enumeration failed (or no songs found): load the known built-ins directly by path.
            System.err.println("[bamfmenu] song discovery found none via enumeration; using built-in fallback");
            for (String id : FALLBACK_IDS) {
                SongDef sd = load(id);
                if (sd != null) {
                    out.add(sd);
                }
            }
        }
        out.sort(Comparator.comparingInt(SongDef::order).thenComparing(SongDef::id));
        for (int i = 1; i < out.size(); i++) {
            if (RECOVERY_ID.equals(out.get(i).id())) {
                out.add(0, out.remove(i)); // pin Recovery first (SONGS[0] contract), wherever it sorted
                break;
            }
        }
        System.err.println("[bamfmenu] song discovery: " + out.size() + " song(s)");
        return out.toArray(new SongDef[0]);
    }

    /* A song's offered difficulties (DATA-DRIVEN): the song.json "difficulties" array if present (a custom named
       set, e.g. an osu import's Beginner/Normal/Hyper/Another/Green Another), else the default easy/normal/hard
       derived from which <token>.chart actually exist (+ SPECIAL if the song declares a special session). The
       chartExists predicate abstracts in-jar (built-in) vs on-disk (user) chart lookup. Never null; sorted by
       order. Every EXISTING song with no "difficulties" array is unchanged. */
    private static List<SongDifficulty> loadDifficulties(Json.Obj o, java.util.function.Predicate<String> chartExists,
            boolean hasSpecial) {
        List<SongDifficulty> out = new ArrayList<>();
        List<Json.Obj> declared = o.objs("difficulties");
        if (!declared.isEmpty()) {
            int idx = 0;
            for (Json.Obj d : declared) {
                String token = sanitizeId(d.str("token", d.str("name", "")));
                if (!token.isEmpty()) {
                    out.add(new SongDifficulty(d.str("name", token), token, d.i("order", idx), d.f("sensDelta", 0f)));
                }
                idx++;
            }
            out.sort(Comparator.comparingInt(SongDifficulty::order));
            return out;
        }
        for (Difficulty d : new Difficulty[]{Difficulty.EASY, Difficulty.NORMAL, Difficulty.HARD}) {
            if (chartExists.test(d.token)) {
                out.add(new SongDifficulty(d.label, d.token, d.ordinal(), d.sensDelta));
            }
        }
        if (hasSpecial) {
            out.add(new SongDifficulty(Difficulty.SPECIAL.label, Difficulty.SPECIAL.token, Difficulty.SPECIAL.ordinal(), 0f));
        }
        return out;
    }

    /* Build one song from its descriptor. Every field defaults (missing detector / detector field falls back
       to DetectorConfig.defaults()) so a partial or forward-incompatible song.json never throws. */
    private static SongDef load(String id) {
        try (InputStream in = SongLoader.class.getResourceAsStream("/songs/" + id + "/song.json")) {
            if (in == null) {
                return null;
            }
            Json.Obj o = Json.object(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            String name = o.str("name", id);
            String label = o.str("label", name);
            int order = o.i("order", 1000); // unspecified songs sort after the curated ones
            String ogg = "/songs/" + id + "/" + o.str("ogg", id + ".ogg");
            double bpm = o.dbl("bpm", 0);

            DetectorConfig base = DetectorConfig.defaults();
            Json.Obj d = o.obj("detector"); // empty Obj if absent -> every field defaults
            DetectorConfig detector = new DetectorConfig(
                    d.f("sensitivity", base.sensitivity()),
                    d.i("leadInMs", base.leadInMs()),
                    d.f("cdFloor", base.cdFloor()),
                    d.dbl("melLoHz", base.melLoHz()),
                    d.dbl("melHiHz", base.melHiHz()),
                    d.dbl("percHiHz", base.percHiHz()),
                    d.i("tailMs", base.tailMs()),
                    d.dbl("minF0Hz", base.minF0Hz()));

            Builtin b = BUILTINS.get(id);
            Supplier<LeftPanelTrack> panel = b != null ? b.panel() : null;
            Supplier<SpecialSession> special = b != null ? b.special() : null;
            List<SongDifficulty> diffs = loadDifficulties(o,
                    token -> SongLoader.class.getResource("/songs/" + id + "/" + token + ".chart") != null,
                    special != null);
            return new SongDef(id, name, label, order, ogg, bpm, detector, panel, special, diffs);
        } catch (Throwable t) {
            return null; // a malformed/unreadable descriptor drops only this song
        }
    }

    /* The set of song folder ids = subdirectories of /songs that contain a song.json. The dev exploded
       resource dir is a `file:` directory we can list directly; the packaged module jar exposes /songs via a
       `union:` (or `jar:`) URL where directory listing is unreliable, so we instead resolve the containing jar
       on disk and scan its ZIP entries -- proven to work in this environment. */
    private static Set<String> songIds() {
        Set<String> ids = new LinkedHashSet<>();
        try {
            URL url = SongLoader.class.getResource("/songs");
            if (url != null && "file".equals(url.getProtocol())) {
                Path dir = Paths.get(url.toURI());
                if (Files.isDirectory(dir)) {
                    try (var stream = Files.newDirectoryStream(dir, Files::isDirectory)) {
                        for (Path p : stream) {
                            if (Files.exists(p.resolve("song.json"))) {
                                ids.add(p.getFileName().toString());
                            }
                        }
                    }
                }
            }
            if (ids.isEmpty()) {
                // union: / jar: / module layer -> find the jar on disk and scan it.
                File jar = jarFileFromUrl(url);
                if (jar == null) {
                    jar = jarFileFromCodeSource();
                }
                if (jar != null) {
                    scanJar(jar, ids);
                }
            }
        } catch (Throwable t) {
            System.err.println("[bamfmenu] song discovery error: " + t);
        }
        return ids;
    }

    /* Resolve the filesystem path of the jar referenced by a resource/code-source URL, across the schemes the
       FML module loader uses. Handles `jar:file:/...jar!/songs`, cpw `union:/...jar%23NNN!/songs` (the %23NNN /
       #NNN is a union index marker), and a plain `file:/...jar`. Returns null if it isn't a real jar file
       (e.g. an exploded `file:` directory, handled separately). */
    private static File jarFileFromUrl(URL url) {
        if (url == null) {
            return null;
        }
        try {
            String s = url.toString();
            int bang = s.indexOf("!/");                 // jar-entry separator: keep only the jar part
            String head = bang >= 0 ? s.substring(0, bang) : s;
            head = head.replaceFirst("^union:", "").replaceFirst("^jar:", "").replaceFirst("^file:", "");
            // Strip ONLY the trailing cpw union index marker -- a "%23NNN" (encoded '#NNN') or literal "#NNN"
            // at the very END of the jar part -- anchored with $ so a real folder whose name contains '#'
            // (which arrives mid-path as "%23") is NOT truncated.
            head = head.replaceFirst("(%23|#)\\d+$", "");
            // RFC 3986 path decode (%XX only). NOT java.net.URLDecoder: that is form-decoding, so it maps '+'
            // to a space (cpw leaves '+' literal in the path) and throws on a lone '%' -- both corrupt real
            // install paths (e.g. a versioned "...+build" mods folder).
            String dec = decodePath(head);
            if (dec.matches("/[A-Za-z]:/.*")) {
                dec = dec.substring(1);                 // "/C:/path" -> "C:/path" on Windows
            }
            File f = new File(dec);
            return f.isFile() ? f : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static File jarFileFromCodeSource() {
        try {
            var cs = SongLoader.class.getProtectionDomain().getCodeSource();
            return cs != null ? jarFileFromUrl(cs.getLocation()) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /* RFC 3986 percent-decoding (%XX only). Deliberately NOT java.net.URLDecoder, which is
       application/x-www-form-urlencoded: it maps '+' to a space and throws IllegalArgumentException on a lone
       '%'. cpw's union/jar URLs embed the raw filesystem path with sub-delims like '+' left literal, and a
       folder may legitimately contain a bare '%', so we must neither translate '+' nor throw. These URLs are
       ASCII (any non-ASCII byte arrives as %XX, reassembled here into UTF-8). */
    private static String decodePath(String s) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    bos.write((hi << 4) + lo);
                    i += 2;
                    continue;
                }
            }
            bos.write(c); // ASCII char or an incomplete '%' escape -> kept literal
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /* Scan a jar's entries for songs/<id>/song.json, collecting the <id> folder names. Opens our OWN ZipFile
       handle (closed here), independent of the classloader's. */
    private static void scanJar(File jar, Set<String> ids) {
        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> e = zip.entries();
            while (e.hasMoreElements()) {
                String n = e.nextElement().getName(); // e.g. songs/recovery/song.json
                if (n.startsWith("songs/") && n.endsWith("/song.json")) {
                    String mid = n.substring("songs/".length(), n.length() - "/song.json".length());
                    if (!mid.isEmpty() && mid.indexOf('/') < 0) {
                        ids.add(mid);
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("[bamfmenu] song jar scan error: " + t);
        }
    }

    /* List classpath resource paths directly under /<dir>/ whose FILENAME matches `regex` (e.g. the Tetris game's
       background music: dir="songs", regex="tetris\\d+\\.ogg"). Uses the SAME union/jar enumeration as song
       discovery (a file: dev dir is listed; otherwise the containing jar is resolved + its ZIP entries scanned).
       DATA-DRIVEN: drop in another matching file and it joins automatically. Returns "/<dir>/<file>" paths sorted
       for a stable order; never null. */
    public static List<String> listResources(String dir, String regex) {
        Set<String> out = new LinkedHashSet<>();
        java.util.regex.Pattern pat = java.util.regex.Pattern.compile(regex);
        try {
            URL url = SongLoader.class.getResource("/" + dir);
            if (url != null && "file".equals(url.getProtocol())) {
                Path d = Paths.get(url.toURI());
                if (Files.isDirectory(d)) {
                    try (var stream = Files.newDirectoryStream(d, Files::isRegularFile)) {
                        for (Path f : stream) {
                            String fn = f.getFileName().toString();
                            if (pat.matcher(fn).matches()) {
                                out.add("/" + dir + "/" + fn);
                            }
                        }
                    }
                }
            }
            if (out.isEmpty()) {
                File jar = jarFileFromUrl(url);
                if (jar == null) {
                    jar = jarFileFromCodeSource();
                }
                if (jar != null) {
                    String prefix = dir + "/";
                    try (ZipFile zip = new ZipFile(jar)) {
                        Enumeration<? extends ZipEntry> e = zip.entries();
                        while (e.hasMoreElements()) {
                            String n = e.nextElement().getName();
                            if (n.startsWith(prefix)) {
                                String rest = n.substring(prefix.length());
                                if (!rest.isEmpty() && rest.indexOf('/') < 0 && pat.matcher(rest).matches()) {
                                    out.add("/" + n);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("[bamfmenu] listResources error: " + t);
        }
        List<String> list = new ArrayList<>(out);
        list.sort(Comparator.naturalOrder());
        return list;
    }

    /* ===================== on-disk user songs (editor) ===================== */

    /* Discover on-disk user songs under <gameDir>/BAMFOS/songs/. Each folder with a song.json (or just an OGG,
       for which a minimal song.json is auto-filled) becomes a SongDef whose ogg is a "file:"-prefixed disk path
       (AudioPlayer reads it off-disk). TOLERANT: a folder with no usable OGG, or an unreadable descriptor, is
       skipped + logged -- never throws, since shared folders will be imperfect. id = folder name. */
    public static List<SongDef> discoverDisk(Path songsDir) {
        List<SongDef> out = new ArrayList<>();
        if (songsDir == null) {
            return out;
        }
        ensureSongsRootReadme(songsDir); // create the songs dir + a one-time starter readme.md (never overwritten)
        if (!Files.isDirectory(songsDir)) {
            return out;
        }
        try (var dirs = Files.newDirectoryStream(songsDir, Files::isDirectory)) {
            for (Path dir : dirs) {
                SongDef sd = loadDisk(dir);
                if (sd != null) {
                    out.add(sd);
                }
            }
        } catch (Throwable t) {
            System.err.println("[bamfmenu] user-song discovery error: " + t);
        }
        out.sort(Comparator.comparing(SongDef::id));
        return out;
    }

    /* Build a SongDef from an on-disk folder. song.json is read if present; otherwise it is INFERRED from the
       folder name + the first OGG (the auto-fill: a bare folder with just an OGG is still chartable) and a
       minimal song.json is written so the folder becomes complete. Returns null (skip) only with no usable OGG. */
    private static SongDef loadDisk(Path dir) {
        try {
            String id = dir.getFileName().toString();
            Path jsonPath = dir.resolve("song.json");
            boolean hadJson = Files.isReadable(jsonPath);
            Json.Obj o = Json.object(hadJson ? Files.readString(jsonPath, StandardCharsets.UTF_8) : "{}"); // empty -> all defaults
            String name = o.str("name", id);
            String label = o.str("label", name);
            int order = o.i("order", 1000);
            double bpm = o.dbl("bpm", 0);
            String oggName = o.str("ogg", null);
            Json.Obj dj = o.obj("detector");
            DetectorConfig base = DetectorConfig.defaults();
            DetectorConfig detector = new DetectorConfig(
                    dj.f("sensitivity", base.sensitivity()), dj.i("leadInMs", base.leadInMs()),
                    dj.f("cdFloor", base.cdFloor()), dj.dbl("melLoHz", base.melLoHz()),
                    dj.dbl("melHiHz", base.melHiHz()), dj.dbl("percHiHz", base.percHiHz()),
                    dj.i("tailMs", base.tailMs()), dj.dbl("minF0Hz", base.minF0Hz()));
            // One song per folder: more than one SONG OGG (intro.ogg excluded) is ambiguous -> flag it (don't
            // scaffold/pick) so the editor refuses with "use only one song per chart" until the extra is removed.
            List<Path> oggs = songOggs(dir);
            if (oggs.size() > 1) {
                return new SongDef(id, name, label, order, MULTI_OGG, bpm, detector, null, null, java.util.List.of());
            }
            Path ogg = oggName != null ? dir.resolve(oggName) : null;
            boolean manifestOk = ogg != null && Files.isReadable(ogg)
                    && !ogg.getFileName().toString().equalsIgnoreCase(INTRO_OGG); // the manifest must not point at the intro
            if (!manifestOk) {
                ogg = oggs.isEmpty() ? null : oggs.get(0);
            }
            ensureSongScaffold(dir); // complete song.json (if absent) + guaranteed normal.chart + empty panels/
            if (ogg == null) {
                // No audio yet (e.g. a freshly "Create New" song, or a user folder still missing its OGG). LIST it
                // (ogg == null marks it audio-less) so the user sees it + is told to add an OGG; the editor refuses
                // to open an audio-less song until one is dropped in. The folder is still scaffolded + portable.
                return new SongDef(id, name, label, order, null, bpm, detector, null, null, java.util.List.of());
            }
            // DATA-DRIVEN difficulties: the song.json "difficulties" array (osu's custom set) or the easy/normal/
            // hard charts that exist on disk (computed AFTER scaffold so the guaranteed normal.chart is counted).
            List<SongDifficulty> diffs = loadDifficulties(o, token -> Files.exists(dir.resolve(token + ".chart")), false);
            String oggRef = "file:" + ogg.toAbsolutePath();
            return new SongDef(id, name, label, order, oggRef, bpm, detector, null, null, diffs);
        } catch (Throwable t) {
            System.err.println("[bamfmenu] user song '" + dir.getFileName() + "' skipped: " + t);
            return null;
        }
    }

    /* The readable SONG OGGs in a folder (intro.ogg excluded), sorted by name for a stable "first". One song per
       folder is the rule, so this list having >1 entry is the "too many songs" condition. */
    private static List<Path> songOggs(Path dir) {
        List<Path> out = new ArrayList<>();
        try (var s = Files.newDirectoryStream(dir, "*.ogg")) {
            for (Path p : s) {
                if (Files.isReadable(p) && !p.getFileName().toString().equalsIgnoreCase(INTRO_OGG)) {
                    out.add(p);
                }
            }
        } catch (Throwable ignored) {
        }
        out.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return out;
    }

    private static Path firstOgg(Path dir) {
        List<Path> o = songOggs(dir);
        return o.isEmpty() ? null : o.get(0);
    }

    /* Ensure an on-disk song folder is a COMPLETE, portable, data-driven song -- the ONE canonical template the
       editor lays down everywhere (forks, drop-ins, every save, "create new"). Idempotent + NON-DESTRUCTIVE: it
       only ADDS what is missing, never overwrites authored files. Guarantees, per the pack's rule that every
       song has a Normal chart:
         (1) a COMPLETE song.json manifest if none exists (name/label/order/ogg/bpm/detector -- not the old
             name/ogg/bpm-only stub), so the folder is immediately portable + shareable;
         (2) a BLANK normal.chart if none exists -- so a song authored Easy/Hard-first still gets its required
             Normal, and a fork of a default keeps whatever charts we already authored (copied verbatim, never
             clobbered here);
         (3) an empty panels/ asset folder so the art home is laid out ready (owner: a bare empty folder).
       Best-effort; never throws (callable on the render thread). */
    public static void ensureSongScaffold(Path dir) {
        if (dir == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
            String id = dir.getFileName().toString();
            Path jsonPath = dir.resolve("song.json");
            if (!Files.exists(jsonPath)) {
                Path ogg = firstOgg(dir);
                String oggName = ogg != null ? ogg.getFileName().toString() : (id + ".ogg");
                Files.writeString(jsonPath,
                        "{\n"
                                + "  \"name\": \"" + jsonEscape(id) + "\",\n"
                                + "  \"label\": \"" + jsonEscape(id) + "\",\n"
                                + "  \"order\": 1000,\n"
                                + "  \"ogg\": \"" + jsonEscape(oggName) + "\",\n"
                                + "  \"bpm\": 0,\n"
                                + "  \"detector\": {}\n"
                                + "}\n",
                        StandardCharsets.UTF_8);
            }
            Path normal = dir.resolve(Difficulty.NORMAL.token + ".chart"); // the REQUIRED default chart
            if (!Files.exists(normal)) {
                Files.writeString(normal,
                        ChartIO.write(new Chart(Difficulty.NORMAL.token, readBpm(jsonPath), 0, 0, new ArrayList<>())),
                        StandardCharsets.UTF_8);
            }
            Files.createDirectories(dir.resolve("panels")); // bare empty asset home
        } catch (Throwable t) {
            System.err.println("[bamfmenu] song scaffold failed for " + dir + ": " + t);
        }
    }

    /* The bpm recorded in a folder's song.json (0 if none/unreadable), so a scaffolded blank chart carries the
       song's declared tempo when the manifest already has one. */
    private static double readBpm(Path jsonPath) {
        try {
            if (Files.isReadable(jsonPath)) {
                return Json.object(Files.readString(jsonPath, StandardCharsets.UTF_8)).dbl("bpm", 0);
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /* Minimal JSON string escaping for the hand-written manifest (folder names can't legally hold " or \ on
       Windows, but be defensive so a pasted-in id never produces malformed JSON). */
    private static String jsonEscape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /* The BAMFOS/songs/ root gets a one-time readme.md the first time the editor touches the folder: the SIXX
       HANDS logo on top, then a bare-bones map (mermaid + plain-text tree) of a song folder + the rule that a
       song needs an OGG. CREATE-ONLY-IF-ABSENT -- an existing readme (even hand-edited, even with different
       content) is never touched. */
    public static void ensureSongsRootReadme(Path songsDir) {
        if (songsDir == null) {
            return;
        }
        try {
            Files.createDirectories(songsDir);
            Path readme = songsDir.resolve("readme.md");
            if (Files.exists(readme)) {
                return; // already there -> leave it alone (no overwrite, no byte-compare)
            }
            Files.writeString(readme, buildSongsReadme(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            System.err.println("[bamfmenu] songs readme write failed: " + t);
        }
    }

    private static String buildSongsReadme() {
        StringBuilder sb = new StringBuilder();
        String[] logo = Art.loadArt("/ui/sixxhands.txt"); // the same logo the BIOS header draws
        sb.append("```\n");
        if (logo != null && logo.length > 0) {
            for (String line : logo) {
                sb.append(line).append('\n');
            }
        } else {
            sb.append("SIXX HANDS\n");
        }
        sb.append("```\n\n");
        sb.append("# BAMFOS Songs\n\n");
        sb.append("Drop a song folder here -- one folder per song.\n\n");
        sb.append("```mermaid\n");
        sb.append("graph TD\n");
        sb.append("  S[\"songs/\"] --> A[\"your-song/\"]\n");
        sb.append("  A --> J[\"song.json -- what the song is\"]\n");
        sb.append("  A --> O[\"your-song.ogg -- the audio (REQUIRED)\"]\n");
        sb.append("  A --> N[\"normal.chart -- the default chart\"]\n");
        sb.append("  A --> E[\"easy.chart / hard.chart -- optional\"]\n");
        sb.append("  A --> P[\"panels/ -- art goes here\"]\n");
        sb.append("```\n\n");
        sb.append("```\n");
        sb.append("songs/\n");
        sb.append("  your-song/\n");
        sb.append("    song.json      what the song is\n");
        sb.append("    your-song.ogg  the audio (REQUIRED)\n");
        sb.append("    normal.chart   the default chart\n");
        sb.append("    easy.chart     optional\n");
        sb.append("    hard.chart     optional\n");
        sb.append("    panels/        art goes here\n");
        sb.append("```\n\n");
        sb.append("New song? You must provide an .ogg, or the chart editor won't let you edit a chart.\n\n");
        sb.append("## Editor keys\n\n");
        sb.append("Song / difficulty menus:\n");
        sb.append("```\n");
        sb.append("UP / DOWN   move selection\n");
        sb.append("ENTER       open (or create / Create New Song)\n");
        sb.append("R           reset a difficulty's chart to empty (difficulty menu)\n");
        sb.append("ESC         back / exit\n");
        sb.append("```\n\n");
        sb.append("Timeline editor:\n");
        sb.append("```\n");
        sb.append("LEFT/DOWN/UP/RIGHT   place a note in lane 1/2/3/4 at the cursor\n");
        sb.append(", / .                move the cursor (hold SHIFT to nudge the offset)\n");
        sb.append("HOME / END           cursor to the song start / end\n");
        sb.append("[ / ]                change the snap division (1/4, 1/8, ...)\n");
        sb.append("G                    snap on / off\n");
        sb.append("Q / W                BPM down / up\n");
        sb.append("9 / 0                note fall speed: slower / faster (this chart only)\n");
        sb.append("- / =                zoom out / in\n");
        sb.append("; / '  (or wheel)    pan the view\n");
        sb.append("SPACE                play / stop the audio from the cursor\n");
        sb.append("P / O                test-play from the cursor / from the start\n");
        sb.append("DEL / BACKSPACE      delete the nearest note\n");
        sb.append("S                    save\n");
        sb.append("ESC                  back (asks to confirm if unsaved)\n");
        sb.append("```\n\n");
        sb.append("Mouse: click to place/select a note, drag a note to move it (lane + time),\n");
        sb.append("right-click a note to delete, drag the top waveform strip to scrub the view.\n\n");
        sb.append("peace out yo\n");
        return sb.toString();
    }

    /* Reduce an OGG base name to a safe on-disk folder id: replace the characters Windows forbids in a name (and
       any control char) with '_', trim, and drop trailing dots/spaces (also illegal on Windows). */
    public static String sanitizeId(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == '\\' || c == '/' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String out = sb.toString().trim();
        while (out.endsWith(".") || out.endsWith(" ")) {
            out = out.substring(0, out.length() - 1).trim();
        }
        return out;
    }

    /* Best-effort: point a folder's song.json "ogg" field at the actual OGG file in it (used after a rename so the
       folder mirrors the OGG's name). SURGICAL -- only the ogg value is rewritten; every other field (detector,
       etc.) is preserved. If there's no ogg field or anything fails, discovery still resolves the OGG via the
       firstOgg fallback, so this not applying is harmless. */
    public static void patchOggField(Path dir) {
        try {
            Path jsonPath = dir.resolve("song.json");
            Path ogg = firstOgg(dir);
            if (ogg == null || !Files.isReadable(jsonPath)) {
                return;
            }
            String oggName = ogg.getFileName().toString();
            String text = Files.readString(jsonPath, StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"ogg\"\\s*:\\s*\"[^\"]*\"").matcher(text);
            if (m.find()) {
                String patched = text.substring(0, m.start()) + "\"ogg\": \"" + oggName + "\"" + text.substring(m.end());
                if (!patched.equals(text)) {
                    Files.writeString(jsonPath, patched, StandardCharsets.UTF_8);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /* Fork: copy a bundled song's WHOLE folder (every /songs/<id>/* resource) to destDir on disk, so the fork is
       a self-contained data-driven song (OGG + song.json + charts + panels), identical in layout to the in-jar
       folder. Best-effort; returns whether anything was copied. */
    public static boolean copyBundledSong(String id, Path destDir) {
        try {
            Files.createDirectories(destDir);
            boolean any = false;
            for (String rel : bundledSongFiles(id)) {
                try (InputStream in = SongLoader.class.getResourceAsStream("/songs/" + id + "/" + rel)) {
                    if (in == null) {
                        continue;
                    }
                    Path dest = destDir.resolve(rel);
                    if (dest.getParent() != null) {
                        Files.createDirectories(dest.getParent());
                    }
                    Files.write(dest, in.readAllBytes());
                    any = true;
                }
            }
            return any;
        } catch (Throwable t) {
            System.err.println("[bamfmenu] fork copy failed for " + id + ": " + t);
            return false;
        }
    }

    /* The relative paths of every resource under /songs/<id>/ (e.g. "song.json", "recovery.ogg", "normal.chart",
       "panels/face/lane0.txt"), for the fork copy. Dev exploded dir -> walk it; packaged jar -> scan ZIP entries. */
    private static List<String> bundledSongFiles(String id) {
        List<String> rels = new ArrayList<>();
        String prefix = "songs/" + id + "/";
        try {
            URL url = SongLoader.class.getResource("/songs/" + id);
            if (url != null && "file".equals(url.getProtocol())) {
                Path base = Paths.get(url.toURI());
                try (var walk = Files.walk(base)) {
                    walk.filter(Files::isRegularFile).forEach(p -> rels.add(base.relativize(p).toString().replace('\\', '/')));
                }
                return rels;
            }
            File jar = jarFileFromUrl(SongLoader.class.getResource("/songs"));
            if (jar == null) {
                jar = jarFileFromCodeSource();
            }
            if (jar != null) {
                try (ZipFile zip = new ZipFile(jar)) {
                    Enumeration<? extends ZipEntry> e = zip.entries();
                    while (e.hasMoreElements()) {
                        String n = e.nextElement().getName();
                        if (n.startsWith(prefix) && !n.endsWith("/")) {
                            rels.add(n.substring(prefix.length()));
                        }
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("[bamfmenu] bundled song file scan error for " + id + ": " + t);
        }
        return rels;
    }

    /* Recovery's reactive face: a FaceFrameAnimation built from Recovery's OWN editable frame resources. The
       worked example of "register your own face frames" -- any song can supply its own set the same way.
       Missing files load empty and the animation degrades (falls back to the chord look). Built fresh per
       play via the SongDef supplier. Moved here verbatim from SongRegistry in . */
    private static LeftPanelTrack recoveryFace() {
        String[][] lanes = new String[][]{
                Art.loadArt("/songs/recovery/panels/face/lane0.txt"),
                Art.loadArt("/songs/recovery/panels/face/lane1.txt"),
                Art.loadArt("/songs/recovery/panels/face/lane2.txt"),
                Art.loadArt("/songs/recovery/panels/face/lane3.txt"),
        };
        String[] chord = Art.loadArt("/songs/recovery/panels/face/multi.txt");
        String[] miss = Art.loadArt("/songs/recovery/panels/face/miss.txt");
        String[] idle = Art.loadArt("/songs/recovery/panels/face/idle.txt"); // optional; empty -> holds last/chord
        int[] colors = Art.LANE_COLORS;
        LeftPanelAnimation faces = new FaceFrameAnimation(
                lanes, chord, miss, idle, colors, 0xFFFFFFFF, colors[3], 0xFFAAAAAA);
        return LeftPanelTrack.single(faces);
    }
}
