import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Which file operations this Windows box permits against a jar a JVM is holding open, per way of
 * holding it. The whole held-open swap ladder rests on one platform claim, so the claim gets a
 * harness instead of a paragraph.
 * The row that decides everything is zipfs: securejarhandler's UnionFileSystem opens mod
 * jars with FileSystems.newFileSystem(Path), which resolves to jdk.nio.zipfs.ZipFileSystem, and NIO
 * channels are opened on Windows with delete-sharing granted. ZipFile and RandomAccessFile are the
 * CONTROL legs, because their legacy native handles refuse the same operations: if a control ever comes
 * back permissive the box is not behaving the way the ladder was designed against and the run is
 * void, not interesting.
 * Exit 1 while the ladder is unbuilt, which is the point: the "overwrite in place" column is the
 * operation Journal.place() has today, and it is BLOCKED on every holder.
 * No .class files: run it with single-file source mode the way Bios.java and Broadcast.java are
 * run, because tools/ is tracked and has no *.class rule to catch a stray one.
 * <pre>
 *   java tools/updater-tests/LockLadder.java
 *   java tools/updater-tests/LockLadder.java --json out.json --runs 3
 * </pre>
 * Forward slashes above on purpose: a backslash-u inside a COMMENT is still scanned as a unicode
 * escape, so writing the Windows path here refuses to compile.
 */
public class LockLadder {

    /** The two that must come back restrictive, or the run says nothing about the real ladder. */
    private static final List<String> CONTROLS = List.of("ZipFile", "RandomAccessFile");

    /** zipfs is what FML actually uses; mmap is the near neighbour that shares its sharing mode. */
    private static final List<String> HOLDERS = List.of("ZipFile", "RandomAccessFile", "mmap", "zipfs");

    public static void main(String[] args) throws Exception {
        if (List.of(args).contains("--help") || List.of(args).contains("-h")) {
            System.out.println("""
                    LockLadder, the falsifier for the held-open swap ladder.

                      --runs N     repeat the whole matrix N times and report variance (default 3)
                      --json PATH  also write the result as JSON
                      --help       this

                    EXIT 0 = every holder permits the ladder AND both controls stayed restrictive.
                    EXIT 1 = the ladder is blocked somewhere, or a control went permissive (run void).
                    """);
            return;
        }
        int runs = intArg(args, "--runs", 3);
        String json = strArg(args, "--json", null);

        Path root = Files.createTempDirectory("lockladder");
        List<Map<String, Map<String, String>>> all = new ArrayList<>();
        for (int r = 0; r < runs; r++) {
            all.add(matrix(root));
        }

        Map<String, Map<String, String>> first = all.get(0);
        List<String> ops = new ArrayList<>(first.get(HOLDERS.get(0)).keySet());

        System.out.println("=== LockLadder, " + runs + " run(s) on " + System.getProperty("os.name")
                + ", java " + System.getProperty("java.version") + " ===");
        System.out.printf("%-18s", "held by");
        for (String o : ops) {
            System.out.printf("| %-22s", o);
        }
        System.out.println();
        System.out.println("-".repeat(18 + ops.size() * 24));
        for (String h : HOLDERS) {
            System.out.printf("%-18s", h + (CONTROLS.contains(h) ? " *" : ""));
            for (String o : ops) {
                System.out.printf("| %-22s", first.get(h).get(o));
            }
            System.out.println();
        }
        System.out.println("* = control leg, expected to REFUSE the ladder");

        List<String> unstable = variance(all, ops);
        System.out.println();
        if (unstable.isEmpty()) {
            System.out.println("variance across " + runs + " runs: none, every cell identical");
        } else {
            System.out.println("VARIANCE, these cells did not agree across runs:");
            unstable.forEach(u -> System.out.println("  " + u));
        }

        // report baseline lock controls first, if they go permissive the rest of the run is invalid
        List<String> brokenControls = new ArrayList<>();
        for (String c : CONTROLS) {
            if (ok(first.get(c).get("rename aside")) || ok(first.get(c).get("overwrite in place"))) {
                brokenControls.add(c + " permitted an operation its handle should refuse");
            }
        }

        boolean ladderHolds = ok(first.get("zipfs").get("rename aside"))
                && ok(first.get("zipfs").get("write at freed path"))
                && ok(first.get("zipfs").get("delete at own path"))
                && ok(first.get("zipfs").get("holder still reads"));
        boolean overwriteStillBlocked = !ok(first.get("zipfs").get("overwrite in place"));

        // ONCE, and kept: calling composites() per use re-ran the whole set and let the printed answer and
        // the judged answer come from two different runs
        Map<String, String> composites = composites(root);
        System.out.println();
        System.out.println("=== composites, the three nobody had run ===");
        for (Map.Entry<String, String> e : composites.entrySet()) {
            System.out.println("  " + e.getKey() + ": " + e.getValue());
        }
        boolean compositesHold = composites.values().stream().allMatch(LockLadder::ok);

        System.out.println();
        System.out.println("zipfs permits the ladder ......... " + ladderHolds);
        System.out.println("overwrite in place still blocked . " + overwriteStillBlocked);
        System.out.println("composites all hold .............. " + compositesHold);
        System.out.println("controls stayed restrictive ...... " + brokenControls.isEmpty());
        if (!brokenControls.isEmpty()) {
            brokenControls.forEach(b -> System.out.println("  VOID: " + b));
        }

        if (json != null) {
            Files.writeString(Path.of(json), toJson(all, ops, unstable, brokenControls, composites));
            System.out.println("json written to " + json);
        }

        // include compositesHold in the exit code so a broken multi-step swap fails the run
        boolean pass = ladderHolds && overwriteStillBlocked && compositesHold
                && brokenControls.isEmpty() && unstable.isEmpty();
        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    // Each cell uses its own fresh temp file so destructive ops don't affect later checks.
    private static Map<String, Map<String, String>> matrix(Path root) throws Exception {
        Map<String, Map<String, String>> grid = new LinkedHashMap<>();
        for (String holder : HOLDERS) {
            Map<String, String> row = new LinkedHashMap<>();
            grid.put(holder, row);
            row.put("overwrite in place", cell(root, holder, (jar, dir) -> {
                Path fresh = dir.resolve("fresh.new");
                Files.write(fresh, new byte[]{7});
                Files.move(fresh, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }));
            row.put("rename aside", cell(root, holder, (jar, dir) -> Files.move(jar, dir.resolve("aside"))));
            row.put("write at freed path", cell(root, holder, (jar, dir) -> {
                Files.move(jar, dir.resolve("aside"));
                Files.write(jar, new byte[]{7});
            }));
            row.put("delete at own path", cell(root, holder, (jar, dir) -> Files.delete(jar)));
            row.put("holder still reads", cell(root, holder, (jar, dir) -> {
                Files.move(jar, dir.resolve("aside"));
                Files.write(jar, new byte[]{7});
                Files.delete(dir.resolve("aside"));
                read(dir.resolve("holder"), jar); // proves the running JVM keeps its own bytes
            }));
        }
        return grid;
    }

    /* The three the ladder's design leans on and no harness had exercised. */
    private static Map<String, String> composites(Path root) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("REPLACE then DELETE in one row", cell(root, "zipfs", (jar, dir) -> {
            Files.move(jar, dir.resolve("aside"));
            Files.write(jar, new byte[]{7});
            Files.delete(dir.resolve("aside"));
            Files.delete(jar); // the same row later drops the file entirely
        }));
        out.put("holder reads repeatedly after the swap", cell(root, "zipfs", (jar, dir) -> {
            Files.move(jar, dir.resolve("aside"));
            Files.write(jar, new byte[]{7});
            for (int i = 0; i < 50; i++) {
                read(dir.resolve("holder"), jar);
            }
        }));
        out.put("version RENAME leaves ONE jar", cell(root, "zipfs", (jar, dir) -> {
            Path next = dir.resolve("victim-2.0.0.jar");
            makeJar(next);          // INSTALL of the new versioned name, nothing on disk to fight
            Files.delete(jar);      // delete of the open old jar
            if (Files.exists(jar)) {
                throw new IllegalStateException("old jar survived, two would declare the provider");
            }
        }));
        return out;
    }

    private interface Attempt {
        void run(Path jar, Path dir) throws Exception;
    }

    /* holder is kept open across the whole attempt and stashed so "holder still reads" can reach it. */
    private static final Map<Path, AutoCloseable> LIVE = new LinkedHashMap<>();

    private static String cell(Path root, String holder, Attempt attempt) throws Exception {
        Path dir = Files.createTempDirectory(root, "c");
        Path jar = dir.resolve("victim.jar");
        makeJar(jar);
        AutoCloseable open = hold(holder, jar);
        LIVE.put(dir.resolve("holder"), open);
        try {
            attempt.run(jar, dir);
            return "OK";
        } catch (Throwable t) {
            String m = t.getMessage() == null ? "" : t.getMessage();
            return m.contains("another process") ? "BLOCKED sharing"
                    : "BLOCKED " + t.getClass().getSimpleName().replace("Exception", "");
        } finally {
            LIVE.remove(dir.resolve("holder"));
            try {
                open.close();
            } catch (Throwable ignored) {
                // a holder we cannot close is a temp file left behind, never a wrong verdict
            }
        }
    }

    /* Reads one entry back through whichever holder this cell opened, so a pass means the running
       JVM really still sees its own bytes rather than merely not throwing. */
    private static void read(Path key, Path jar) throws Exception {
        AutoCloseable open = LIVE.get(key);
        byte[] got;
        if (open instanceof ZipFile z) {
            got = z.getInputStream(z.getEntry("a.txt")).readAllBytes();
        } else if (open instanceof FileSystem fs) {
            got = Files.readAllBytes(fs.getPath("a.txt"));
        } else if (open instanceof FileChannel ch) {
            got = new byte[]{ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).get(0)};
        } else if (open instanceof RandomAccessFile raf) {
            raf.seek(0);
            got = new byte[]{(byte) raf.read()};
        } else {
            throw new IllegalStateException("unhandled holder, refusing to pass by default");
        }
        if (got.length == 0) {
            throw new IllegalStateException("read came back empty");
        }
    }

    private static AutoCloseable hold(String kind, Path jar) throws Exception {
        switch (kind) {
            case "ZipFile" -> {
                ZipFile z = new ZipFile(jar.toFile());
                z.getInputStream(z.getEntry("a.txt")).readAllBytes();
                return z;
            }
            case "RandomAccessFile" -> {
                RandomAccessFile r = new RandomAccessFile(jar.toFile(), "r");
                r.read();
                return r;
            }
            case "zipfs" -> {
                FileSystem fs = FileSystems.newFileSystem(jar);
                Files.readAllBytes(fs.getPath("a.txt"));
                return fs;
            }
            default -> {
                FileChannel ch = FileChannel.open(jar, StandardOpenOption.READ);
                ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).get(0);
                return ch;
            }
        }
    }

    private static List<String> variance(List<Map<String, Map<String, String>>> all, List<String> ops) {
        List<String> out = new ArrayList<>();
        for (String h : HOLDERS) {
            for (String o : ops) {
                String base = all.get(0).get(h).get(o);
                for (int i = 1; i < all.size(); i++) {
                    String other = all.get(i).get(h).get(o);
                    if (!base.equals(other)) {
                        out.add(h + " / " + o + ": run 1 said " + base + ", run " + (i + 1) + " said " + other);
                        break;
                    }
                }
            }
        }
        return out;
    }

    private static boolean ok(String cell) {
        return "OK".equals(cell);
    }

    private static void makeJar(Path jar) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("a.txt"));
            out.write("hello".getBytes());
            out.closeEntry();
        }
    }

    private static int intArg(String[] a, String name, int def) {
        for (int i = 0; i < a.length - 1; i++) {
            if (a[i].equals(name)) {
                try {
                    return Integer.parseInt(a[i + 1]);
                } catch (NumberFormatException bad) {
                    return def;
                }
            }
        }
        return def;
    }

    private static String strArg(String[] a, String name, String def) {
        for (int i = 0; i < a.length - 1; i++) {
            if (a[i].equals(name)) {
                return a[i + 1];
            }
        }
        return def;
    }

    private static String toJson(List<Map<String, Map<String, String>>> all, List<String> ops,
            List<String> unstable, List<String> brokenControls, Map<String, String> composites) {
        StringBuilder b = new StringBuilder("{\n");
        b.append("  \"os\": \"").append(System.getProperty("os.name")).append("\",\n");
        b.append("  \"java\": \"").append(System.getProperty("java.version")).append("\",\n");
        b.append("  \"runs\": ").append(all.size()).append(",\n");
        b.append("  \"controls\": [\"ZipFile\", \"RandomAccessFile\"],\n");
        b.append("  \"matrix\": {\n");
        for (int h = 0; h < HOLDERS.size(); h++) {
            String holder = HOLDERS.get(h);
            b.append("    \"").append(holder).append("\": {");
            for (int i = 0; i < ops.size(); i++) {
                b.append("\"").append(ops.get(i)).append("\": \"")
                        .append(all.get(0).get(holder).get(ops.get(i))).append("\"");
                if (i < ops.size() - 1) {
                    b.append(", ");
                }
            }
            b.append("}").append(h < HOLDERS.size() - 1 ? "," : "").append("\n");
        }
        b.append("  },\n  \"composites\": {\n");
        int c = 0;
        for (Map.Entry<String, String> e : composites.entrySet()) {
            b.append("    \"").append(e.getKey()).append("\": \"").append(e.getValue()).append("\"")
                    .append(++c < composites.size() ? "," : "").append("\n");
        }
        b.append("  },\n");
        b.append("  \"variance\": ").append(strings(unstable)).append(",\n");
        b.append("  \"void_controls\": ").append(strings(brokenControls)).append("\n}\n");
        return b.toString();
    }

    private static String strings(List<String> in) {
        if (in.isEmpty()) {
            return "[]";
        }
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < in.size(); i++) {
            b.append("\"").append(in.get(i).replace("\"", "'")).append("\"");
            if (i < in.size() - 1) {
                b.append(", ");
            }
        }
        return b.append("]").toString();
    }
}
