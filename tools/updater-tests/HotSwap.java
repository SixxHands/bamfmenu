import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

// Offline smoke test verifying the built engine jar can hot-swap an open file on Windows (4-file fake instance, no network).
public class HotSwap {

    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || List.of(args).contains("--help")) {
            System.out.println("""
                    HotSwap, the offline check that the shipped engine can swap a jar something holds open.

                      --engine PATH   test this engine jar directly
                      --stub PATH     test the baseline engine embedded in this frozen stub
                      --help          this

                    EXIT 0 = all legs passed. No network, no pack, seconds.
                    """);
            return;
        }
        Path work = Files.createTempDirectory("hotswap");
        Path engine = args[0].equals("--stub") ? baselineOutOf(Path.of(args[1]), work) : Path.of(args[1]);
        if (!Files.isRegularFile(engine)) {
            System.out.println("no engine jar at " + engine);
            System.exit(1);
        }
        System.out.println("=== HotSwap against " + engine.getFileName() + " (" + Files.size(engine) + " bytes) ===");
        System.out.println("    " + engine.toAbsolutePath());

        // check if this jar supports the swap ladder first, everything below is pointless on older jars
        boolean hasLadder = contains(engine, "com/retro/bamfos/updater/engine/journal/Placement.class");
        System.out.println("    carries journal/Placement: " + hasLadder);
        if (!hasLadder) {
            System.out.println();
            System.out.println("FAIL: this engine has no swap ladder in it, so nothing below would prove anything");
            System.exit(1);
        }
        System.out.println();

        try (URLClassLoader cl = new URLClassLoader(new URL[]{engine.toUri().toURL()},
                HotSwap.class.getClassLoader())) {

            // 1. Jar opened via zipfs the way securejarhandler opens mod jars
            leg("swap lands on a zipfs-held jar", work, cl, Hold.ZIPFS, false, true);

            // 2. the control, so a pass above cannot be the plumbing rather than the ladder
            leg("swap lands when nothing holds it", work, cl, Hold.NONE, false, true);

            // 3. no delete-sharing anywhere, so it must defer with the old bytes standing
            leg("a ZipFile-held jar defers, bytes intact", work, cl, Hold.ZIPFILE, false, false);

            // 4. a read-only bit is the user's own hold and outranks the pack
            leg("a read-only jar defers, bytes intact", work, cl, Hold.NONE, true, false);
        }

        System.out.println();
        System.out.println(failed == 0 ? "PASS" : failed + " leg(s) FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }

    private enum Hold { NONE, ZIPFS, ZIPFILE }

    /**
     * One leg: build a four file instance, hold the target however this leg says, replay a one op journal
     * through the engine under test, then read the answer off DISK rather than off the return value.
     */
    private static void leg(String name, Path root, ClassLoader cl, Hold hold, boolean readOnly,
            boolean expectLanded) throws Exception {
        Path dir = Files.createTempDirectory(root, "box");
        Path victim = jar(dir.resolve("mods/victim.jar"), "OLD");
        Path staged = jar(dir.resolve(".bamfos-updater/staging/incoming.part"), "NEW");
        String want = sha256(staged);
        String before = sha256(victim);

        if (readOnly) {
            Files.setAttribute(victim, "dos:readonly", true);
        }
        AutoCloseable holder = open(hold, victim);
        boolean landed;
        try {
            landed = replay(cl, dir, ".bamfos-updater/staging/incoming.part", "mods/victim.jar", want);
        } finally {
            if (holder != null) {
                try {
                    holder.close();
                } catch (Throwable ignored) {
                    // a holder we cannot close is a temp file left behind, never a wrong verdict
                }
            }
            if (readOnly) {
                Files.setAttribute(victim, "dos:readonly", false);
            }
        }

        String now = sha256(victim);
        List<String> wrong = new ArrayList<>();
        if (landed != expectLanded) {
            wrong.add("reported landed=" + landed + ", wanted " + expectLanded);
        }
        // the bytes on disk are the verdict: a report of success over old bytes is the exact lie the ledger
        // gate exists to stop, so this never trusts the return value alone
        if (expectLanded && !want.equals(now)) {
            wrong.add("bytes at the path are not the new ones");
        }
        if (!expectLanded && !before.equals(now)) {
            wrong.add("DEFERRED but the friend's bytes changed anyway");
        }
        if (!Files.isRegularFile(victim)) {
            wrong.add("the name was left EMPTY");
        }
        List<String> litter = strays(dir.resolve("mods"));
        if (expectLanded && !litter.isEmpty()) {
            wrong.add("litter left behind: " + litter);
        }

        System.out.printf("  %-42s %s%n", name, wrong.isEmpty() ? "ok" : "FAILED");
        if (!wrong.isEmpty()) {
            failed++;
            wrong.forEach(w -> System.out.println("      " + w));
        }
    }

    /* Journal is reached by reflection so this harness can load ANY engine jar, including one built months
       ago, without being compiled against it. */
    private static boolean replay(ClassLoader cl, Path dir, String from, String to, String sha) throws Exception {
        Class<?> journalC = cl.loadClass("com.retro.bamfos.updater.engine.journal.Journal");
        Class<?> opC = cl.loadClass("com.retro.bamfos.updater.engine.journal.Journal$Op");
        Object journal = journalC.getConstructor().newInstance();
        journalC.getField("targetVersion").set(journal, "v0.0.0-hotswap");
        Object op = opC.getConstructor(String.class, String.class, String.class, String.class)
                .newInstance("move", from, to, sha);
        @SuppressWarnings("unchecked")
        List<Object> ops = (List<Object>) journalC.getField("ops").get(journal);
        ops.add(op);
        Method replay = journalC.getMethod("replay", Path.class);
        Object result = replay.invoke(journal, dir);
        return (boolean) result.getClass().getMethod("allLanded").invoke(result);
    }

    private static AutoCloseable open(Hold hold, Path jar) throws IOException {
        switch (hold) {
            case ZIPFS -> {
                // literally what cpw.mods.niofs.union.UnionFileSystem does to a mod jar
                FileSystem fs = FileSystems.newFileSystem(jar);
                Files.readAllBytes(fs.getPath("a.txt"));
                return fs;
            }
            case ZIPFILE -> {
                ZipFile z = new ZipFile(jar.toFile());
                z.getInputStream(z.getEntry("a.txt")).readAllBytes();
                return z;
            }
            default -> {
                return null;
            }
        }
    }

    /* Anything beside the target that should not have survived a landed swap. */
    private static List<String> strays(Path mods) throws IOException {
        List<String> out = new ArrayList<>();
        try (var ls = Files.list(mods)) {
            ls.forEach(p -> {
                String n = p.getFileName().toString();
                if (n.contains(".superseded") || n.endsWith(".new")) {
                    out.add(n);
                }
            });
        }
        return out;
    }

    private static Path baselineOutOf(Path stub, Path work) throws IOException {
        Path out = work.resolve("baseline-engine.jar");
        try (ZipFile z = new ZipFile(stub.toFile())) {
            ZipEntry e = z.getEntry("baseline/bamfos-updater-engine.jar");
            if (e == null) {
                System.out.println("that stub carries no baseline/bamfos-updater-engine.jar");
                System.exit(1);
            }
            try (InputStream in = z.getInputStream(e)) {
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return out;
    }

    private static boolean contains(Path jar, String entry) throws IOException {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            return z.getEntry(entry) != null;
        }
    }

    private static Path jar(Path at, String body) throws IOException {
        Files.createDirectories(at.getParent());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(at))) {
            out.putNextEntry(new ZipEntry("a.txt"));
            out.write(body.getBytes());
            out.closeEntry();
        }
        return at;
    }

    private static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder b = new StringBuilder();
        for (byte x : md.digest(Files.readAllBytes(p))) {
            b.append(String.format("%02x", x));
        }
        return b.toString();
    }
}
