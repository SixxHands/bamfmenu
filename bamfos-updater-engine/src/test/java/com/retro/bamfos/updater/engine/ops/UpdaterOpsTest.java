package com.retro.bamfos.updater.engine.ops;

import com.retro.bamfos.updater.engine.Fx;
import com.retro.bamfos.updater.engine.bus.BootBus;
import com.retro.bamfos.updater.engine.config.UpdaterConfig;
import com.retro.bamfos.updater.engine.ledger.Ledger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** the surface the BIOS builds against, offline-testable end to end. */
class UpdaterOpsTest {

    @TempDir
    Path dir;

    private UpdaterOps ops() {
        return new UpdaterOps(dir, "http://127.0.0.1:1");
    }

    @Test
    void status_reportsVersionPinTokenAndCounts() throws IOException {
        Fx.write(dir, "config/bcc-common.toml", "modpackVersion = \"v0.2.4\"\n".getBytes());
        Fx.write(dir, ".bamfos-updater/bamfos-updater.properties", "tag=v0.2.2\n".getBytes());
        Fx.write(dir, ".bamfos-updater/token-dead", "401".getBytes());
        Ledger led = new Ledger();
        led.installedVersion = "v0.2.4";
        Ledger.Entry q = new Ledger.Entry("");
        q.quarantined = true;
        q.disabled = true;
        led.files.put("mods/bad.jar", q);
        Ledger.Entry d = new Ledger.Entry("aa");
        d.disabled = true;
        led.files.put("mods/off.jar", d);
        led.save(Ledger.pathIn(dir));

        Map<String, Object> s = ops().status();
        assertEquals("v0.2.4", s.get("version"));
        assertEquals("v0.2.2", s.get("pinned"));
        assertEquals(Boolean.TRUE, s.get("token_dead"));
        assertEquals(1L, s.get("disabled_count"));
        assertEquals(1L, s.get("quarantined_count"));
    }

    @Test
    void listOptional_mergesManifestOptionals_andQuarantinedWithReasons() throws IOException {
        Fx.write(dir, ".bamfos-updater/manifest-current.json", ("{"
                + "\"schema_version\":1,\"pack\":{},\"files\":["
                + "{\"path\":\"mods/iris.jar\",\"category\":\"optional\"},"
                + "{\"path\":\"mods/core.jar\"}]}").getBytes());
        Fx.write(dir, "mods/iris.jar.disabled", Fx.jar("iris"));
        Ledger led = new Ledger();
        Ledger.Entry q = new Ledger.Entry("");
        q.quarantined = true;
        q.quarantineReason = "named in a server kick (chippedx)";
        led.files.put("mods/chipped-x.jar", q);
        led.save(Ledger.pathIn(dir));

        List<Map<String, Object>> rows = ops().listOptional();
        assertEquals(2, rows.size());
        Map<String, Object> iris = rows.stream().filter(r -> r.get("path").equals("mods/iris.jar")).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, iris.get("disabled"));
        Map<String, Object> bad = rows.stream().filter(r -> r.get("path").equals("mods/chipped-x.jar")).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, bad.get("quarantined"));
        assertTrue(String.valueOf(bad.get("reason")).contains("chippedx"));
    }

    /** Missing both .jar and .jar.disabled is a third state, not "enabled", so deleted mods don't show active. */
    @Test
    void listOptional_reportsAJarThatIsGoneAsMissing() throws IOException {
        Fx.write(dir, ".bamfos-updater/manifest-current.json", ("{"
                + "\"schema_version\":1,\"pack\":{},\"files\":["
                + "{\"path\":\"mods/gone.jar\",\"category\":\"optional\"},"
                + "{\"path\":\"mods/here.jar\",\"category\":\"optional\"},"
                + "{\"path\":\"mods/off.jar\",\"category\":\"optional\"}]}").getBytes());
        Fx.write(dir, "mods/here.jar", Fx.jar("here"));
        Fx.write(dir, "mods/off.jar.disabled", Fx.jar("off"));

        List<Map<String, Object>> rows = ops().listOptional();
        Map<String, Object> gone = row(rows, "mods/gone.jar");
        assertEquals(Boolean.TRUE, gone.get("missing"));
        assertEquals(Boolean.FALSE, gone.get("disabled"));
        assertEquals(Boolean.FALSE, row(rows, "mods/here.jar").get("missing"));
        assertEquals(Boolean.FALSE, row(rows, "mods/here.jar").get("disabled"));
        assertEquals(Boolean.FALSE, row(rows, "mods/off.jar").get("missing"));
        assertEquals(Boolean.TRUE, row(rows, "mods/off.jar").get("disabled"));
    }

    @Test
    void setDisabled_flipsFilenameAndLedgerTogether() throws IOException {
        Fx.write(dir, "mods/iris.jar", Fx.jar("iris"));
        Ledger led = new Ledger();
        led.files.put("mods/iris.jar", new Ledger.Entry("aa"));
        led.save(Ledger.pathIn(dir));

        assertTrue(ops().setDisabled("mods/iris.jar", true));
        assertTrue(Files.exists(dir.resolve("mods/iris.jar.disabled")));
        assertFalse(Files.exists(dir.resolve("mods/iris.jar")));
        assertTrue(Ledger.load(Ledger.pathIn(dir)).files.get("mods/iris.jar").disabled);

        assertTrue(ops().setDisabled("mods/iris.jar", false));
        assertTrue(Files.exists(dir.resolve("mods/iris.jar")));
        assertFalse(Ledger.load(Ledger.pathIn(dir)).files.get("mods/iris.jar").disabled);

        assertTrue(ops().setDisabled("mods/iris.jar", false));
        assertFalse(ops().setDisabled("mods/ghost.jar", true));
    }

    @Test
    void releaseNotes_readsTheCache_emptyWhenNone() throws IOException {
        assertEquals("", ops().releaseNotes());
        Fx.write(dir, ".bamfos-updater/release-notes.json",
                "{\"tag\":\"v0.2.6\",\"headline\":\"h\",\"body\":\"the notes body\"}".getBytes());
        assertEquals("the notes body", ops().releaseNotes());
    }

    @Test
    void gateway_neverThrows_andRefusesUnknownOps() {
        OpsGateway.bind(dir, "http://127.0.0.1:1");
        assertTrue(OpsGateway.invoke("nope", new Object[0]) instanceof String s && s.startsWith("error:"));
        assertInstanceOf(Map.class, OpsGateway.invoke("status", new Object[0]));
        assertEquals("", OpsGateway.invoke("releaseNotes", new Object[0]));
    }

    /** Rate limits need their own exit code instead of generic failure (code 2), so the BIOS repair
     *  screen can tell the player to wait rather than claiming the sync is broken. */
    @Test
    void stagedSync_reportsARateLimitAsItsOwnCode_notAsFailure() throws IOException {
        com.sun.net.httpserver.HttpServer gh = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        gh.createContext("/repos/o/r/releases/tags/v1", ex -> {
            ex.getResponseHeaders().add("x-ratelimit-remaining", "0");
            ex.sendResponseHeaders(403, -1);
        });
        gh.start();
        try {
            Fx.write(dir, ".bamfos-updater/bamfos-updater.properties", "repo=o/r\ngithub_token=tok\n".getBytes());
            UpdaterOps ops = new UpdaterOps(dir, "http://127.0.0.1:" + gh.getAddress().getPort());
            assertEquals(5, ops.syncTo("v1"));
        } finally {
            gh.stop(0);
        }
    }

    /**
     * In-session sync must append to bus.log without truncating (poll() bails if size shrinks below its offset)
     * and emit END on every exit so syncActive() unlocks UI actions.
     */
    @Test
    void stagedSync_appendsToTheLiveBus_andAlwaysEndsIt() throws IOException {
        Fx.write(dir, "config/bcc-common.toml", "modpackVersion = \"v0.2.4\"\n".getBytes());
        Path busFile = dir.resolve(".bamfos-updater").resolve("bus.log");
        Files.createDirectories(busFile.getParent());
        Files.writeString(busFile, "LOG\tthe boot feed is already reading this\n");

        // apiBase points to a closed port so sync fails; make sure END is still written.
        assertEquals(2, ops().syncTo("v0.2.9"));

        List<String> lines = Files.readAllLines(busFile);
        assertEquals("LOG\tthe boot feed is already reading this", lines.get(0));
        assertEquals("END\tfail", lines.get(lines.size() - 1));
    }

    /**
     * Use the same latin-1 fallback as UpdaterConfig.rawProps so ANSI-saved properties files don't break writePin.
     */
    @Test
    void writePin_survivesAnAnsiSavedPropertiesFile_andKeepsTheirBytes() throws IOException {
        Path p = dir.resolve(".bamfos-updater").resolve("bamfos-updater.properties");
        Files.createDirectories(p.getParent());
        // 0xE9 alone is a legal latin-1 'e-acute' and an ILLEGAL utf-8 sequence, exactly what Notepad's ANSI
        // save leaves behind when a player types an accent in a comment
        byte[] ansi = new byte[]{'#', ' ', (byte) 0xE9, '\n', 'r', 'e', 'p', 'o', '=', 'o', '/', 'r', '\n'};
        Files.write(p, ansi);

        assertDoesNotThrow(() -> ops().writePin("v0.2.2"), "the boot path reads this file fine, so must we");

        byte[] after = Files.readAllBytes(p);
        assertTrue(new String(after, java.nio.charset.StandardCharsets.ISO_8859_1).contains("tag=v0.2.2"));
        assertTrue(indexOf(after, (byte) 0xE9) >= 0);
        assertEquals("o/r", com.retro.bamfos.updater.engine.config.UpdaterConfig.load(dir).repo());
    }

    /** The rollback picker's data source. A repo accumulates releases forever and this feeds a
     *  MENU, so the cap is the feature; a draft is a row that could only ever fail the sync a player
     *  started from it, because /releases/tags/&lt;tag&gt; cannot resolve one. */
    @Test
    void listReleases_capsTheMenu_dropsDrafts_andMarksWhatIsInstalled() throws IOException {
        java.util.concurrent.atomic.AtomicReference<String> query = new java.util.concurrent.atomic.AtomicReference<>("");
        StringBuilder json = new StringBuilder("[{\"tag_name\":\"v9.9\",\"name\":\"staging\",\"draft\":true}");
        for (int i = 12; i >= 1; i--) { // newest first, the order github answers in
            json.append(",{\"tag_name\":\"v0.").append(i)
                    .append("\",\"name\":\"BAMFOS v0.").append(i)
                    .append("\",\"published_at\":\"").append(String.format("2026-07-%02dT00:00:00Z", i))
                    .append("\",\"prerelease\":").append(i == 5).append("}");
        }
        json.append("]");
        com.sun.net.httpserver.HttpServer gh = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        gh.createContext("/repos/o/r/releases", ex -> {
            query.set(String.valueOf(ex.getRequestURI().getQuery()));
            byte[] b = json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (java.io.OutputStream out = ex.getResponseBody()) {
                out.write(b);
            }
        });
        gh.start();
        try {
            Fx.write(dir, "config/bcc-common.toml", "modpackVersion = \"v0.4\"\n".getBytes());
            Fx.write(dir, ".bamfos-updater/bamfos-updater.properties", "repo=o/r\ngithub_token=tok\n".getBytes());
            String api = "http://127.0.0.1:" + gh.getAddress().getPort();

            Map<String, Object> out = new UpdaterOps(dir, api).listReleases();
            assertEquals(0, out.get("code"));
            assertEquals("v0.4", out.get("installed"));
            List<Map<String, Object>> rows = rows(out);
            assertEquals(UpdaterOps.MAX_RELEASES, rows.size());
            assertEquals("v0.12", rows.get(0).get("tag"));
            assertEquals("BAMFOS v0.12", rows.get(0).get("name"));
            assertEquals("2026-07-12T00:00:00Z", rows.get(0).get("published_at"));
            assertTrue(rows.stream().noneMatch(r -> "v9.9".equals(r.get("tag"))));
            assertTrue(rows.stream().noneMatch(r -> "v0.2".equals(r.get("tag"))));
            assertEquals(Boolean.TRUE, pick(rows, "v0.4").get("installed"));
            assertEquals(Boolean.FALSE, pick(rows, "v0.12").get("installed"));
            assertEquals(Boolean.TRUE, pick(rows, "v0.5").get("prerelease"));
            assertTrue(query.get().contains("per_page=" + UpdaterOps.MAX_RELEASES));

            OpsGateway.bind(dir, api);
            assertTrue(OpsGateway.invoke("listReleases", new Object[0]) instanceof Map<?, ?> m
                    && Integer.valueOf(0).equals(m.get("code")), "the shim's reflective bridge has to reach it");
        } finally {
            gh.stop(0);
        }
    }

    /** spells the offline rule out: the picker shows current only. So an unreachable github can
     *  neither throw (the page would have to interpret a wifi blip) nor answer with an empty list, which
     *  to the player reading it says this repo has no releases at all. */
    @Test
    void listReleases_offlineStillNamesTheInstalledVersion() throws IOException {
        Fx.write(dir, "config/bcc-common.toml", "modpackVersion = \"v0.2.4\"\n".getBytes());

        Map<String, Object> out = ops().listReleases(); // apiBase is a closed port
        assertEquals(2, out.get("code"));
        assertEquals("v0.2.4", out.get("installed"));
        List<Map<String, Object>> rows = rows(out);
        assertEquals(1, rows.size());
        assertEquals("v0.2.4", rows.get(0).get("tag"));
        assertEquals(Boolean.TRUE, rows.get(0).get("installed"));
        assertEquals(java.util.Set.of("tag", "name", "published_at", "prerelease", "installed"),
                rows.get(0).keySet());
    }

    /** Keep rate-limit (5) and auth failure (4) separate from generic offline errors so the UI
     *  can show the right message for each, same as sync. */
    @Test
    void listReleases_keepsAThrottleAndADeadTokenOffTheOfflineCode() throws IOException {
        com.sun.net.httpserver.HttpServer gh = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        gh.createContext("/limited/repos/o/r/releases", ex -> {
            ex.getResponseHeaders().add("x-ratelimit-remaining", "0");
            ex.sendResponseHeaders(403, -1);
        });
        gh.createContext("/dead/repos/o/r/releases", ex -> ex.sendResponseHeaders(401, -1));
        gh.start();
        try {
            Fx.write(dir, "config/bcc-common.toml", "modpackVersion = \"v0.2.4\"\n".getBytes());
            Fx.write(dir, ".bamfos-updater/bamfos-updater.properties", "repo=o/r\ngithub_token=tok\n".getBytes());
            String base = "http://127.0.0.1:" + gh.getAddress().getPort();

            assertEquals(5, new UpdaterOps(dir, base + "/limited").listReleases().get("code"));
            Map<String, Object> dead = new UpdaterOps(dir, base + "/dead").listReleases();
            assertEquals(4, dead.get("code"));
            assertEquals(1, rows(dead).size());
        } finally {
            gh.stop(0);
        }
    }

    /** Clearing a pin is a separate op because syncTo("") would remove tag= and then try to sync an
     *  empty tag. Like writePin, it preserves latin-1 bytes so user comments and other settings stay
     *  untouched. */
    @Test
    void unpin_dropsOnlyTheTagLine_andReportsAnHonestNoOp() throws IOException {
        Path p = dir.resolve(".bamfos-updater").resolve("bamfos-updater.properties");
        Files.createDirectories(p.getParent());
        // 0xE9 is valid Latin-1 (e-acute) but invalid UTF-8, matching what an ANSI editor writes.
        Files.write(p, ("# " + (char) 0xE9 + "\nrepo=o/r\ntag=v0.2.2\nauto_restart=false\n")
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

        assertEquals(0, ops().unpin());
        byte[] after = Files.readAllBytes(p);
        String text = new String(after, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(text.contains("tag="));
        assertTrue(text.contains("repo=o/r") && text.contains("auto_restart=false"));
        assertTrue(indexOf(after, (byte) 0xE9) >= 0);
        assertEquals("", com.retro.bamfos.updater.engine.config.UpdaterConfig.load(dir).tagPin());

        assertEquals(1, ops().unpin());
        Path fresh = Files.createDirectories(dir.resolve("fresh"));
        assertEquals(1, new UpdaterOps(fresh, "http://127.0.0.1:1").unpin());
        assertFalse(Files.exists(fresh.resolve(".bamfos-updater").resolve("bamfos-updater.properties")));
        OpsGateway.bind(dir, "http://127.0.0.1:1");
        assertEquals(1, OpsGateway.invoke("unpin", new Object[0]));
    }

    /**
     * java.util.Properties accepts `=`, `:`, and whitespace separators, so writeKeys has to match all of
     * them instead of just `tag=`, otherwise unpin() leaves the pin behind without error.
     */
    @Test
    void unpin_clearsThePinHoweverTheHumanSpacedIt() throws IOException {
        String[] forms = {"tag = v0.2.4", "tag:v0.2.4", "tag v0.2.4", "  tag=v0.2.4"};
        for (int i = 0; i < forms.length; i++) {
            Path box = Files.createDirectories(dir.resolve("box" + i));
            Fx.write(box, ".bamfos-updater/bamfos-updater.properties",
                    ("repo=o/r\n" + forms[i] + "\n# tag=v0.0.1 is the friend's own note\n").getBytes());
            UpdaterOps ops = new UpdaterOps(box, "http://127.0.0.1:1");
            assertEquals("v0.2.4", UpdaterConfig.load(box).tagPin(), forms[i] + ": the reader sees a pin");

            assertEquals(0, ops.unpin(), forms[i] + ": a pin that is read has to be clearable");
            assertEquals("", UpdaterConfig.load(box).tagPin(), forms[i] + ": and gone means gone");
            assertEquals("o/r", UpdaterConfig.load(box).repo());
            assertTrue(Files.readString(box.resolve(".bamfos-updater/bamfos-updater.properties"))
                    .contains("# tag=v0.0.1"));
            assertEquals(1, ops.unpin(), forms[i] + ": the second clear is an honest no-op");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> listReleases) {
        return (List<Map<String, Object>>) listReleases.get("releases");
    }

    private static Map<String, Object> pick(List<Map<String, Object>> rows, String tag) {
        return rows.stream().filter(r -> tag.equals(r.get("tag"))).findFirst().orElseThrow();
    }

    private static Map<String, Object> row(List<Map<String, Object>> rows, String path) {
        return rows.stream().filter(r -> path.equals(r.get("path"))).findFirst().orElseThrow();
    }

    private static int indexOf(byte[] hay, byte needle) {
        for (int i = 0; i < hay.length; i++) {
            if (hay[i] == needle) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Checks bus event names and the "ok" END state verbatim. UpdaterFeed in earlywindow matches these
     * strings from bus.log, so renaming any of them breaks boot UI rendering.
     */
    @Test
    void bootBus_framesEventsAndStripsTabs() throws IOException {
        BootBus bus = BootBus.at(dir);
        bus.line("updater: packages current (v0.2.4), nothing to do");
        bus.ident("o/r", "1.0.1", "");
        bus.syncBegin("o/r", "v1", "v2");
        bus.fileDone("weird\tname.jar");
        bus.note("PACK UPDATE v2, the headline");
        bus.manifest(270);
        bus.plan(12);
        bus.fileStart("cm.jar");
        bus.applying();
        bus.synced("v1", "v2", true);
        bus.end("ok");

        assertEquals(List.of(
                "LOG\tupdater: packages current (v0.2.4), nothing to do",
                "IDENT\to/r\t1.0.1\t", // an unpinned box sends an EMPTY third field, never the word none
                "SYNC\to/r\tv1\tv2",
                "FDONE\tweird name.jar", // Tabs inside the payload shouldn't break field parsing.
                "NOTE\tPACK UPDATE v2, the headline",
                "MANIFEST\t270",
                "PLAN\t12",
                "FILE\tcm.jar",
                "PHASE\tApplying",
                "SYNCED\tv1\tv2",
                "PENDING",
                "END\tok"), Files.readAllLines(dir.resolve(".bamfos-updater").resolve("bus.log")));
        // a new boot truncates
        BootBus.at(dir);
        assertEquals(0, Files.size(dir.resolve(".bamfos-updater").resolve("bus.log")));
    }
}
