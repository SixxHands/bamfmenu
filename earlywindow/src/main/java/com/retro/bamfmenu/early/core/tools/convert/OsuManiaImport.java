package com.retro.bamfmenu.early.core.tools.convert;

import com.retro.bamfmenu.early.core.rhythm.model.Note;
import com.retro.bamfmenu.early.core.rhythm.engine.RhythmEngine;
import com.retro.bamfmenu.early.core.rhythm.chart.Chart;
import com.retro.bamfmenu.early.core.rhythm.chart.ChartIO;
import com.retro.bamfmenu.early.core.rhythm.registry.SongLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/* DEV TOOL: convert an osu!MANIA beatmap into a BAMF! song folder (the data-driven layout the game/editor read).
   Input is a .osz (zip), a single .osu, or an extracted beatmap folder; output is <out>/<id>/ with song.json + the
   chart(s) + the audio (if OGG) + an empty panels/, ready to drag into BAMFOS/songs/ (to chart/test) or
   resources/songs/ (to bundle). MANIA ONLY: non-mania (Mode != 3) charts are skipped.

   Mapping (deterministic): column = floor(x * keyCount / 512); lane = column*4/keyCount (4K is 1:1, other key
   counts are proportionally remapped to our 4 lanes, approximate). Hold/long notes (type bit 128) carry their
   endTime as holdMs. Times are absolute ms, so they map straight across. BPM from the first uninherited timing
   point. A set's multiple difficulties are assigned by note density: 1 -> normal; 2 -> normal+hard; 3+ ->
   easy/normal/hard (extras dropped), always producing the REQUIRED normal.

   Audio caveat: osu songs are usually .mp3, and the engine decodes OGG only (STB Vorbis). If the beatmap ships an
   .ogg it's copied (immediately playable); if it's only .mp3, the chart + song.json are still written but the
   folder is left audio-less (the editor shows "(needs audio)" until you convert the mp3 -> ogg and drop it in).

   Pure stdlib (text + java.util.zip), no LWJGL/natives, so it runs on the plain runtime classpath, like genSong.
   Run: gradlew :earlywindow:importOsu -Posu="<path>" [-Pout="<dir>"]. */
public final class OsuManiaImport {

    /* Holds shorter than this become plain TAPS. osu!mania is full of very short long-notes (1/8-1/16-beat LNs the
       mapper uses as a texture); at our scale a hold this brief is (a) indistinguishable from a tap to our judge,
       a quick tap's release already lands inside the tail window and can never DROP (the early-release cutoff is
       ~100ms), and (b) renders as a degenerate 1-2 row capsule. So a sub-window "hold" is noise: keep it a tap.
       Value tracks our hit window (RhythmEngine OKAY/EARLY ~= 100ms). Tunable per-import via -PminHold. */
    private static final int DEFAULT_MIN_HOLD_MS = 100;

    private OsuManiaImport() {
    }

    public static void main(String[] args) {
        if (args.length < 1 || args[0].isBlank()) {
            System.out.println("Usage: gradlew :earlywindow:importOsu -Posu=<.osz | .osu | folder> [-Pout=<dir>] [-PminHold=<ms>]");
            return;
        }
        Path input = Path.of(args[0]);
        Path outRoot = Path.of(args.length >= 2 && !args[1].isBlank() ? args[1] : "build/osu-import");
        String diffSel = args.length >= 3 ? args[2] : "";
        String ffmpegPath = args.length >= 4 ? args[3] : "";
        int minHold = args.length >= 5 && !args[4].isBlank() ? parseIntSafe(args[4], DEFAULT_MIN_HOLD_MS) : DEFAULT_MIN_HOLD_MS;
        try {
            run(input, outRoot, diffSel, ffmpegPath, minHold);
        } catch (Throwable t) {
            System.err.println("[osu] import failed: " + t);
        }
    }

    /* A parsed source: the raw .osu texts + the chosen audio (OGG only; mp3 just flags hadMp3). */
    private static final class Source {
        final List<String> osu = new ArrayList<>();
        byte[] ogg;
        String oggName;
        Path mp3Path;     // disk source (folder / .osu input), transcoded in place
        byte[] mp3Bytes;  // zip source (.osz input), written to a temp file then transcoded
        String mp3Name;
        boolean hadMp3;
    }

    /* One parsed mania difficulty. version = the osu [Version] name (e.g. "Beginner", "Hyper"), used for -Pdiffs
       selection + the listing. */
    private record Diff(String title, String artist, String version, int keyCount, int bpm, List<Note> notes) {
    }

    private static void run(Path input, Path outRoot, String diffSel, String ffmpegPath, int minHold) throws IOException {
        if (!Files.exists(input)) {
            System.out.println("[osu] not found: " + input);
            return;
        }
        Source src = new Source();
        String low = input.getFileName().toString().toLowerCase();
        if (low.endsWith(".osz")) {
            readOsz(input, src);
        } else if (Files.isDirectory(input)) {
            for (Path p : list(input, ".osu")) {
                src.osu.add(Files.readString(p, StandardCharsets.UTF_8));
            }
            scanAudioDir(input, src);
        } else if (low.endsWith(".osu")) {
            src.osu.add(Files.readString(input, StandardCharsets.UTF_8));
            if (input.getParent() != null) {
                scanAudioDir(input.getParent(), src);
            }
        } else {
            System.out.println("[osu] unsupported input (need .osz, .osu, or a folder): " + input);
            return;
        }

        List<Diff> diffs = new ArrayList<>();
        for (String text : src.osu) {
            Diff d = parse(text);
            if (d != null && !d.notes().isEmpty()) {
                diffs.add(d);
            }
        }
        if (diffs.isEmpty()) {
            System.out.println("[osu] no osu!mania (Mode 3) charts with notes found in " + input);
            return;
        }
        int collapsed = collapseShortHolds(diffs, minHold); // sub-window LNs -> taps (osu trill-LNs are a mess for us)
        if (collapsed > 0) {
            System.out.println("[osu] collapsed " + collapsed + " short hold(s) (< " + minHold + "ms) to taps");
        }
        diffs.sort(Comparator.comparingInt(d -> d.notes().size())); // ascending density

        // Always show what's available so you know the names to pick with -Pdiffs.
        StringBuilder avail = new StringBuilder();
        for (Diff d : diffs) {
            avail.append("[").append(d.version()).append("](").append(d.notes().size()).append(") ");
        }
        System.out.println("[osu] available difficulties: " + avail.toString().trim());

        // -Pdiffs="name,name,..." selects specific osu difficulties (case-insensitive); empty = use all.
        List<Diff> chosen = select(diffSel, diffs);
        if (chosen.isEmpty()) {
            System.out.println("[osu] no difficulties matched -Pdiffs=\"" + diffSel + "\" -- use the names listed above.");
            return;
        }
        chosen.sort(Comparator.comparingInt(d -> d.notes().size()));

        Diff meta = chosen.get(chosen.size() / 2); // any chosen diff has the set's title/artist
        String title = meta.title().isBlank() ? "osu song" : meta.title();
        String artist = meta.artist();
        String id = SongLoader.sanitizeId(title);
        if (id.isEmpty()) {
            id = "osu-song";
        }

        Path dir = outRoot.resolve(id);
        Files.createDirectories(dir);
        Files.createDirectories(dir.resolve("panels"));

        // audio: copy an OGG as-is, or transcode an MP3 -> OGG via ffmpeg (if present), else leave audio-less.
        String[] audio = ensureAudio(src, dir, id, ffmpegPath);
        String oggName = audio[0];
        String label = artist.isBlank() ? title : title + " by " + artist;
        int bpm = meta.bpm();

        /* DIFFICULTY MAPPING (data-driven):
           - <=3 chosen diffs: density-map to easy/normal/hard (always producing the REQUIRED normal); song.json
             carries NO "difficulties" array, so the game derives them from which <token>.chart exist (simple case).
           - >3 chosen diffs ("OSU class"): keep EVERY chosen diff as its own <token>.chart named for the osu
             [Version] (slugged), and write a song.json "difficulties" array (name + token + density order) so the
             jukebox shows the FULL custom set in its scrolling difficulty menu, nothing is dropped. */
        String diffsJson = "";
        String mappedLog;
        if (chosen.size() > 3) {
            List<String> tokens = new ArrayList<>();
            StringBuilder arr = new StringBuilder("  \"difficulties\": [\n");
            StringBuilder log = new StringBuilder();
            for (int i = 0; i < chosen.size(); i++) {
                Diff d = chosen.get(i);
                String name = d.version().isBlank() ? ("Diff " + (i + 1)) : d.version();
                String token = uniqueToken(slug(name), tokens);
                tokens.add(token);
                writeChart(dir, token, d); // ascending density -> order 0 is the easiest
                arr.append("    { \"name\": \"").append(esc(name)).append("\", \"token\": \"").append(esc(token))
                        .append("\", \"order\": ").append(i).append(" }").append(i < chosen.size() - 1 ? ",\n" : "\n");
                log.append("[").append(name).append("->").append(token).append("](").append(d.notes().size()).append(") ");
            }
            arr.append("  ],\n");
            diffsJson = arr.toString();
            mappedLog = "all " + chosen.size() + " kept (OSU class): " + log.toString().trim();
        } else {
            Diff easy = null;
            Diff normal;
            Diff hard = null;
            if (chosen.size() == 1) {
                normal = chosen.get(0);
            } else if (chosen.size() == 2) {
                normal = chosen.get(0);
                hard = chosen.get(1);
            } else {
                easy = chosen.get(0);
                normal = chosen.get(chosen.size() / 2);
                hard = chosen.get(chosen.size() - 1);
            }
            bpm = normal.bpm();
            writeChart(dir, "easy", easy);
            writeChart(dir, "normal", normal);
            writeChart(dir, "hard", hard);
            mappedLog = (easy != null ? "easy<-[" + easy.version() + "](" + easy.notes().size() + ") " : "")
                    + "normal<-[" + normal.version() + "](" + normal.notes().size() + ") "
                    + (hard != null ? "hard<-[" + hard.version() + "](" + hard.notes().size() + ")" : "");
        }

        String json = "{\n"
                + "  \"name\": \"" + esc(title) + "\",\n"
                + "  \"label\": \"" + esc(label) + "\",\n"
                + "  \"order\": 1000,\n"
                + "  \"ogg\": \"" + esc(oggName != null ? oggName : id + ".ogg") + "\",\n"
                + "  \"bpm\": " + bpm + ",\n"
                + diffsJson
                + "  \"detector\": {}\n"
                + "}\n";
        Files.writeString(dir.resolve("song.json"), json, StandardCharsets.UTF_8);

        System.out.println("[osu] imported '" + title + "' -> " + dir);
        System.out.println("[osu]   mapped: " + mappedLog + "   bpm " + bpm + "   keys " + meta.keyCount());
        if (meta.keyCount() != 4) {
            System.out.println("[osu]   note: source is " + meta.keyCount() + "K; remapped to 4 lanes (approximate).");
        }
        System.out.println("[osu]   audio: " + audio[1]);
        System.out.println("[osu] Done. Drag '" + id + "' into BAMFOS/songs/ (to chart/test) or resources/songs/ (to bundle).");
    }

    /* Pick which osu difficulties to convert. Blank = all; otherwise a comma list of [Version] names, matched
       case-insensitively (exact first, then a 'contains' fallback). Unmatched names are warned + skipped. The
       chosen set is then density-mapped to easy/normal/hard by the caller. */
    private static List<Diff> select(String diffSel, List<Diff> all) {
        if (diffSel == null || diffSel.isBlank()) {
            return new ArrayList<>(all);
        }
        List<Diff> out = new ArrayList<>();
        for (String raw : diffSel.split(",")) {
            String name = raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            Diff m = findByVersion(all, name);
            if (m == null) {
                System.out.println("[osu]   note: no difficulty named '" + name + "' (skipped).");
            } else if (!out.contains(m)) {
                out.add(m);
            }
        }
        return out;
    }

    private static Diff findByVersion(List<Diff> all, String name) {
        for (Diff d : all) {
            if (d.version().equalsIgnoreCase(name)) {
                return d;
            }
        }
        for (Diff d : all) {
            if (d.version().toLowerCase().contains(name.toLowerCase())) {
                return d;
            }
        }
        return null;
    }

    /* Ensure the song folder has a playable OGG. Copies an OGG straight through; transcodes an MP3 -> OGG via
       ffmpeg when ffmpeg is on PATH (or in the winget/choco link dir); otherwise leaves it audio-less. Returns
       {oggFileName-or-null, human status}. */
    private static String[] ensureAudio(Source src, Path dir, String id, String ffmpegPath) throws IOException {
        if (src.ogg != null) {
            // name the copied OGG after the folder id (NOT the source's generic "audio.ogg"): keeps song.json's
            // ogg field, the file, and the folder name all in sync so the editor's mirror-rename is a no-op, and
            // two imports never collide on a shared "audio" name when dropped into BAMFOS/songs/.
            String name = id + ".ogg";
            Files.write(dir.resolve(name), src.ogg);
            return new String[]{name, name + " (ogg, ready to play)"};
        }
        if (src.hadMp3) {
            String ffmpeg = findFfmpeg(ffmpegPath);
            if (ffmpeg == null) {
                return new String[]{null,
                        "source is .mp3 and ffmpeg was not found -- install ffmpeg then re-run, or drop a .ogg in (folder shows '(needs audio)')"};
            }
            Path mp3 = src.mp3Path;
            Path temp = null;
            if (mp3 == null && src.mp3Bytes != null) {
                temp = Files.createTempFile("osu-import-", ".mp3");
                Files.write(temp, src.mp3Bytes);
                mp3 = temp;
            }
            boolean ok = mp3 != null && transcode(ffmpeg, mp3, dir.resolve(id + ".ogg"));
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignored) {
                }
            }
            if (ok) {
                return new String[]{id + ".ogg", "transcoded .mp3 -> " + id + ".ogg via ffmpeg (ready to play)"};
            }
            return new String[]{null, ".mp3 transcode FAILED via ffmpeg -- convert it to .ogg manually + drop it in"};
        }
        return new String[]{null, "none found -- drop a song.ogg into the folder"};
    }

    /* Locate ffmpeg: an explicit override first, then PATH, the winget/chocolatey link dirs, and any ffmpeg
       bundled with CapCut (newest version first; CapCut ships one, so no install is needed). Null if none run. */
    private static String findFfmpeg(String override) {
        List<String> cands = new ArrayList<>();
        if (override != null && !override.isBlank()) {
            cands.add(override);
        }
        cands.add("ffmpeg");
        String la = System.getenv("LOCALAPPDATA");
        if (la != null) {
            cands.add(la + "\\Microsoft\\WinGet\\Links\\ffmpeg.exe");
        }
        String pd = System.getenv("ProgramData");
        if (pd != null) {
            cands.add(pd + "\\chocolatey\\bin\\ffmpeg.exe");
        }
        if (la != null) {
            cands.addAll(capcutFfmpegs(Path.of(la, "CapCut", "Apps")));
        }
        for (String c : cands) {
            try {
                Process p = new ProcessBuilder(c, "-version").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes(); // drain so it can't block
                if (p.waitFor() == 0) {
                    return c;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /* CapCut's bundled ffmpeg.exe paths under .../CapCut/Apps/<version>/, newest version first. */
    private static List<String> capcutFfmpegs(Path appsDir) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(appsDir)) {
            return out;
        }
        try (var s = Files.newDirectoryStream(appsDir, Files::isDirectory)) {
            List<Path> vers = new ArrayList<>();
            for (Path d : s) {
                Path ff = d.resolve("ffmpeg.exe");
                if (Files.isRegularFile(ff)) {
                    vers.add(ff);
                }
            }
            vers.sort(Comparator.comparing(p -> p.getParent().getFileName().toString()));
            for (int i = vers.size() - 1; i >= 0; i--) {
                out.add(vers.get(i).toString());
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /* Transcode to OGG/Vorbis, dropping any embedded cover-art stream. Tries libvorbis (best quality); falls back
       to the native 'vorbis' encoder (e.g. CapCut's ffmpeg has no libvorbis). Returns success. */
    private static boolean transcode(String ffmpeg, Path mp3, Path dest) {
        String[][] codecs = {
                {"-c:a", "libvorbis", "-q:a", "5"},
                {"-c:a", "vorbis", "-strict", "-2", "-b:a", "192k"},
        };
        for (String[] codec : codecs) {
            try {
                List<String> cmd = new ArrayList<>();
                cmd.add(ffmpeg);
                cmd.add("-y");
                cmd.add("-i");
                cmd.add(mp3.toString());
                cmd.add("-vn");
                for (String a : codec) {
                    cmd.add(a);
                }
                cmd.add(dest.toString());
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                p.getInputStream().readAllBytes(); // drain ffmpeg's progress output
                if (p.waitFor() == 0 && Files.exists(dest) && Files.size(dest) > 0) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static void writeChart(Path dir, String token, Diff d) throws IOException {
        if (d == null) {
            return;
        }
        Files.writeString(dir.resolve(token + ".chart"),
                ChartIO.write(new Chart(token, d.bpm(), 0, 0, new ArrayList<>(d.notes()))), StandardCharsets.UTF_8);
    }

    /* Parse one .osu into a mania Diff, or null if it isn't Mode 3. Tolerant: unknown keys ignored, bad rows skipped. */
    private static Diff parse(String text) {
        int mode = -1;
        int keyCount = 4;
        int bpm = 0;
        String title = "";
        String artist = "";
        String version = "";
        String section = "";
        List<Note> notes = new ArrayList<>();
        for (String raw : text.split("\r?\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line;
                continue;
            }
            switch (section) {
                case "[General]" -> {
                    if (line.startsWith("Mode:")) {
                        mode = parseIntSafe(after(line), -1);
                    }
                }
                case "[Metadata]" -> {
                    if (line.startsWith("Title:")) {
                        title = after(line);
                    } else if (line.startsWith("Artist:")) {
                        artist = after(line);
                    } else if (line.startsWith("Version:")) {
                        version = after(line);
                    }
                }
                case "[Difficulty]" -> {
                    if (line.startsWith("CircleSize:")) {
                        keyCount = Math.max(1, (int) parseDoubleSafe(after(line), 4));
                    }
                }
                case "[TimingPoints]" -> {
                    if (bpm == 0) {
                        String[] f = line.split(",");
                        if (f.length >= 2) {
                            double beat = parseDoubleSafe(f[1], 0); // uninherited (red) lines have a positive beatLength
                            if (beat > 0) {
                                bpm = (int) Math.round(60000.0 / beat);
                            }
                        }
                    }
                }
                case "[HitObjects]" -> {
                    Note n = parseHit(line, keyCount);
                    if (n != null) {
                        notes.add(n);
                    }
                }
                default -> { }
            }
        }
        if (mode != 3) {
            return null;
        }
        return new Diff(title, artist, version, keyCount, bpm, notes);
    }

    /* Turn every hold shorter than minHold into a plain tap (holdMs = 0), across all parsed diffs. Note.holdMs is
       final, so a collapsed hold is REPLACED by a fresh tap Note in place. The note count is unchanged (a tap is
       still a note), so density ordering + the "available difficulties" tallies are unaffected. Returns the count
       collapsed (for the log). minHold <= 0 disables it. */
    private static int collapseShortHolds(List<Diff> diffs, int minHold) {
        if (minHold <= 0) {
            return 0;
        }
        int collapsed = 0;
        for (Diff d : diffs) {
            List<Note> ns = d.notes();
            for (int i = 0; i < ns.size(); i++) {
                Note n = ns.get(i);
                if (n.holdMs > 0 && n.holdMs < minHold) {
                    ns.set(i, new Note(n.lane, n.timeMs, 0));
                    collapsed++;
                }
            }
        }
        return collapsed;
    }

    /* One [HitObjects] row -> a Note, or null if malformed. Format: x,y,time,type,hitSound,(endTime:sample). The
       mania column is derived from x; type bit 128 = a hold (long note) whose endTime is the head of field 6. */
    private static Note parseHit(String line, int keyCount) {
        String[] f = line.split(",");
        if (f.length < 4) {
            return null;
        }
        try {
            int x = Integer.parseInt(f[0].trim());
            int time = Integer.parseInt(f[2].trim());
            int type = Integer.parseInt(f[3].trim());
            if (time < 0) {
                return null;
            }
            int kc = Math.max(1, keyCount);
            int column = (int) ((long) x * kc / 512);
            if (column < 0) {
                column = 0;
            } else if (column >= kc) {
                column = kc - 1;
            }
            int lane = column * 4 / kc; // proportional remap to 4 lanes (4K -> identity)
            if (lane < 0) {
                lane = 0;
            } else if (lane > 3) {
                lane = 3;
            }
            int holdMs = 0;
            if ((type & 128) != 0 && f.length >= 6) {
                String ep = f[5];
                int ci = ep.indexOf(':');
                int end = parseIntSafe(ci >= 0 ? ep.substring(0, ci) : ep, time);
                holdMs = Math.max(0, end - time);
            }
            return new Note(lane, time, holdMs);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void readOsz(Path osz, Source s) throws IOException {
        try (ZipFile zip = new ZipFile(osz.toFile())) {
            Enumeration<? extends ZipEntry> e = zip.entries();
            while (e.hasMoreElements()) {
                ZipEntry ze = e.nextElement();
                if (ze.isDirectory()) {
                    continue;
                }
                String n = ze.getName();
                String low = n.toLowerCase();
                if (low.endsWith(".osu")) {
                    try (InputStream in = zip.getInputStream(ze)) {
                        s.osu.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                } else if (low.endsWith(".ogg") && s.ogg == null) {
                    try (InputStream in = zip.getInputStream(ze)) {
                        s.ogg = in.readAllBytes();
                        s.oggName = fileName(n);
                    }
                } else if (low.endsWith(".mp3")) {
                    if (s.mp3Bytes == null) {
                        try (InputStream in = zip.getInputStream(ze)) {
                            s.mp3Bytes = in.readAllBytes();
                            s.mp3Name = fileName(n);
                        }
                    }
                    s.hadMp3 = true;
                }
            }
        }
    }

    private static void scanAudioDir(Path dir, Source s) throws IOException {
        for (Path p : list(dir, ".ogg")) {
            if (s.ogg == null) {
                s.ogg = Files.readAllBytes(p);
                s.oggName = p.getFileName().toString();
            }
        }
        if (s.ogg == null) {
            List<Path> mp3s = list(dir, ".mp3");
            if (!mp3s.isEmpty()) {
                s.mp3Path = mp3s.get(0);
                s.hadMp3 = true;
            }
        }
    }

    private static List<Path> list(Path dir, String ext) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (var st = Files.newDirectoryStream(dir)) {
            for (Path p : st) {
                if (Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(ext)) {
                    out.add(p);
                }
            }
        }
        out.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return out;
    }

    private static String after(String line) {
        int c = line.indexOf(':');
        return c >= 0 ? line.substring(c + 1).trim() : "";
    }

    private static String fileName(String zipName) {
        int slash = Math.max(zipName.lastIndexOf('/'), zipName.lastIndexOf('\\'));
        return slash >= 0 ? zipName.substring(slash + 1) : zipName;
    }

    private static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static double parseDoubleSafe(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /* Slug an osu [Version] name into a safe lowercase chart token: keep a-z0-9, map space/_/- to '-', drop the
       rest, then collapse + trim dashes. Blank -> "diff". The token names the <token>.chart file AND the score
       key, so it stays filesystem- + map-safe (no spaces/caps); the human name is preserved separately. */
    private static String slug(String s) {
        if (s == null) {
            return "diff";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else if (c == ' ' || c == '_' || c == '-') {
                sb.append('-');
            }
        }
        String out = sb.toString();
        while (out.contains("--")) {
            out = out.replace("--", "-");
        }
        while (out.startsWith("-")) {
            out = out.substring(1);
        }
        while (out.endsWith("-")) {
            out = out.substring(0, out.length() - 1);
        }
        return out.isEmpty() ? "diff" : out;
    }

    /* Disambiguate a token within one import: append -2, -3, ... if it collides with one already used (two osu
       versions that slug the same, e.g. "Another!" and "Another"). */
    private static String uniqueToken(String base, List<String> used) {
        if (!used.contains(base)) {
            return base;
        }
        for (int n = 2; n <= 99; n++) {
            String c = base + "-" + n;
            if (!used.contains(c)) {
                return c;
            }
        }
        return base + "-x";
    }
}
