package com.retro.bamfmenu.early.core.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
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
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/* Classpath enumeration for the early window: list bundled resources under a directory even though the
   packaged FML union: URL can't be listed directly. A dev file: dir is walked; otherwise the containing jar
   is resolved and its ZIP entries are scanned. Started inside SongLoader for song discovery; the secret
   registry and the Tetris playlist borrowed it from there, so it lives in util now, reaching into the song
   loader for a classpath walk was backwards. */
public final class ResourceScan {

    private ResourceScan() {
    }

    /* Resolve the filesystem path of the jar referenced by a resource/code-source URL, across the schemes the
       FML module loader uses. Handles `jar:file:/...jar!/songs`, cpw `union:/...jar%23NNN!/songs` (the %23NNN /
       #NNN is a union index marker), and a plain `file:/...jar`. Returns null if it isn't a real jar file
       (e.g. an exploded `file:` directory, handled separately). */
    public static File jarFileFromUrl(URL url) {
        if (url == null) {
            return null;
        }
        try {
            String s = url.toString();
            int bang = s.indexOf("!/");                 // jar-entry separator: keep only the jar part
            String head = bang >= 0 ? s.substring(0, bang) : s;
            head = head.replaceFirst("^union:", "").replaceFirst("^jar:", "").replaceFirst("^file:", "");
            // Strip ONLY the trailing cpw union index marker, a "%23NNN" (encoded '#NNN') or literal "#NNN"
            // at the very END of the jar part, anchored with $ so a real folder whose name contains '#'
            // (which arrives mid-path as "%23") is NOT truncated.
            head = head.replaceFirst("(%23|#)\\d+$", "");
            // RFC 3986 path decode (%XX only). NOT java.net.URLDecoder: that is form-decoding, so it maps '+'
            // to a space (cpw leaves '+' literal in the path) and throws on a lone '%', both corrupt real
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

    public static File jarFileFromCodeSource() {
        try {
            var cs = ResourceScan.class.getProtectionDomain().getCodeSource();
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

    /* List classpath resource paths directly under /<dir>/ whose FILENAME matches `regex` (e.g. the Tetris game's
       background music: dir="songs", regex="tetris\\d+\\.ogg"). Uses the SAME union/jar enumeration as song
       discovery (a file: dev dir is listed; otherwise the containing jar is resolved + its ZIP entries scanned).
       DATA-DRIVEN: drop in another matching file and it joins automatically. Returns "/<dir>/<file>" paths sorted
       for a stable order; never null. */
    public static List<String> listResources(String dir, String regex) {
        Set<String> out = new LinkedHashSet<>();
        java.util.regex.Pattern pat = java.util.regex.Pattern.compile(regex);
        try {
            URL url = ResourceScan.class.getResource("/" + dir);
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

    /* Like listResources, but matches `pathRegex` against the RELATIVE PATH under /<dir>/ (subdirectories included),
       not just a filename, so a caller can enumerate a nested layout with a shape-enforcing pattern (e.g. the
       secret registry's "one folder per secret, declaring class at its root": dir="com/.../secret",
       pathRegex="[a-z0-9]+/\\w+\\.class" matches exactly one level down and nothing deeper). Same union/jar
       enumeration; returns "/<dir>/<relpath>" sorted; never null. */
    public static List<String> listResourcesDeep(String dir, String pathRegex) {
        Set<String> out = new LinkedHashSet<>();
        java.util.regex.Pattern pat = java.util.regex.Pattern.compile(pathRegex);
        try {
            URL url = ResourceScan.class.getResource("/" + dir);
            if (url != null && "file".equals(url.getProtocol())) {
                // The file branch gets its OWN guard: a walk failure (unreadable dev dir) must degrade to the jar
                // fallback below, not abort the whole scan with a partial result.
                try {
                    Path d = Paths.get(url.toURI());
                    if (Files.isDirectory(d)) {
                        try (var stream = Files.walk(d)) {
                            for (Path f : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                                String rel = d.relativize(f).toString().replace('\\', '/');
                                if (pat.matcher(rel).matches()) {
                                    out.add("/" + dir + "/" + rel);
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    System.err.println("[bamfmenu] listResourcesDeep dir walk failed (trying jar): " + t);
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
                                if (!rest.isEmpty() && !rest.endsWith("/") && pat.matcher(rest).matches()) {
                                    out.add("/" + n);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("[bamfmenu] listResourcesDeep error: " + t);
        }
        List<String> list = new ArrayList<>(out);
        list.sort(Comparator.naturalOrder());
        return list;
    }
}
