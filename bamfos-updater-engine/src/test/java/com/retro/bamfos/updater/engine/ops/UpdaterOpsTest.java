package com.retro.bamfos.updater.engine.ops;

import com.retro.bamfos.updater.engine.Fx;
import com.retro.bamfos.updater.engine.bus.BootBus;
import com.retro.bamfos.updater.engine.config.UpdaterConfig;
import com.retro.bamfos.updater.engine.ledger.Ledger;
import com.retro.bamfos.updater.engine.manifest.Manifest;
import com.retro.bamfos.updater.engine.manifest.ManifestParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
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
        Ledger.Entry d = new Ledger.Entry("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        d.disabled = true;
        led.files.put("mods/off.jar", d);
        led.save(Ledger.pathIn(dir));

        Map<String, Object> s = ops().status();
        assertEquals("v0.2.4", s.get("version"));
        assertEquals("v0.2.2", s.get("pinned"));
        assertEquals(Boolean.TRUE, s.get("token_dead"));
        assertEquals(1L, s.get("disabled_count"));
        assertEquals(1L, s.get("quarantined_count"));
        assertEquals("v0.2.2", s.get("reset_target"));
    }

    /**
     * factoryReset always arms client side, so running it on a server would wipe server files and install
     * client mods. Guard it directly since OpsGateway is public and linked on servers.
     */
    @Test
    void factoryReset_refusesOnAServerRatherThanRebuildingItAsAClient() throws IOException {
        Ledger led = new Ledger();
        led.installedVersion = "v0.2.4";
        led.save(Ledger.pathIn(dir));
        Fx.write(dir, "server.properties", "server-port=25565\nmotd=BAMFOS\n".getBytes());

        Map<String, Object> r = ops().factoryReset();

        assertEquals(6L, r.get("code"));
        String said = String.valueOf(r.get("message"));
        assertTrue(said.contains("server directory"), said);
        assertTrue(said.contains("Nothing was changed"), String.valueOf(said));
        assertFalse(Files.exists(dir.resolve(".bamfos-updater").resolve("journal.json")));
    }

    /**
     * A server that has synced but never booted Minecraft has no server.properties yet, so the server guard
     * must fall back to the ledger to recognize it.
     */
    @Test
    void serverOnlyFileOnDisk_catchesAServerThatHasSyncedButNeverRun() throws IOException {
        // Use the fixture helper instead of hand-typing JSON so we get the right schema_version field.
        Manifest man = ManifestParser.parse("""
                {"schema_version":1,
                 "pack":{"id":"bamfos","version":"v1","mc":"1.21.1","neoforge":"21.1.235","updater_abi":1},
                 "requires_relaunch":false,"removed_modids":[],
                 "files":[
                  {"path":"config/both.toml","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","size":1,"side":"both","policy":"managed",
                   "source":{"type":"release-asset","asset":"x"}},
                  {"path":"config/aeronautics-server.toml","sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","size":1,"side":"server",
                   "policy":"managed","source":{"type":"release-asset","asset":"y"}}]}""");

        Ledger client = new Ledger();
        client.files.put("config/both.toml", new Ledger.Entry("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        assertNull(UpdaterOps.serverOnlyFileOnDisk(man, client));

        Ledger server = new Ledger();
        server.files.put("config/both.toml", new Ledger.Entry("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        server.files.put("config/aeronautics-server.toml", new Ledger.Entry("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
        assertEquals("config/aeronautics-server.toml", UpdaterOps.serverOnlyFileOnDisk(man, server));
    }

    /**
     * Manifests can ship the same path (even with identical sha) as separate client and server rows. Only a
     * path with no client-reachable row counts as a server tell, or fresh clients get falsely refused.
     */
    @Test
    void serverOnlyFileOnDisk_aPathTheClientSideAlsoShipsIsNeverAServerTell() throws IOException {
        Manifest man = ManifestParser.parse("""
                {"schema_version":1,
                 "pack":{"id":"bamfos","version":"v1","mc":"1.21.1","neoforge":"21.1.235","updater_abi":1},
                 "requires_relaunch":false,"removed_modids":[],
                 "files":[
                  {"path":"config/DistantHorizons.toml","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","size":1,"side":"client",
                   "policy":"managed","source":{"type":"release-asset","asset":"dh-client"}},
                  {"path":"config/DistantHorizons.toml","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","size":1,"side":"server",
                   "policy":"managed","source":{"type":"release-asset","asset":"dh-server"}},
                  {"path":"config/aeronautics-server.toml","sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","size":1,"side":"server",
                   "policy":"managed","source":{"type":"release-asset","asset":"y"}}]}""");

        Ledger freshClient = new Ledger();
        freshClient.files.put("config/DistantHorizons.toml", new Ledger.Entry("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        assertNull(UpdaterOps.serverOnlyFileOnDisk(man, freshClient));

        Ledger server = new Ledger();
        server.files.put("config/DistantHorizons.toml", new Ledger.Entry("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        server.files.put("config/aeronautics-server.toml", new Ledger.Entry("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
        assertEquals("config/aeronautics-server.toml", UpdaterOps.serverOnlyFileOnDisk(man, server));
    }

    /** Clients don't have server.properties, so the server guard shouldn't block them; they should
     *  still reach the normal "never synced" error code instead. */
    @Test
    void factoryReset_stillReachesTheClientPath_theGuardIsNotABlanketRefusal() {
        Map<String, Object> r = ops().factoryReset();
        assertNotEquals(6L, r.get("code"));
        assertEquals(1L, r.get("code"));
    }

    /**
     * Reset target uses the pin or ledger, never the bcc stamp that `version` prefers. With no ledger, return
     * empty so the UI shows "unknown" instead of offering a reset that will refuse to run.
     */
    @Test
    void status_resetTargetIsThePinElseTheLedger_neverTheBccStamp() throws IOException {
        Fx.write(dir, "config/bcc-common.toml", "modpackVersion = \"v0.2.5\"\n".getBytes());
        assertEquals("v0.2.5", ops().status().get("version"));
        assertEquals("", ops().status().get("reset_target"));

        Ledger led = new Ledger();
        led.installedVersion = "v0.2.3";
        led.save(Ledger.pathIn(dir));
        assertEquals("v0.2.5", ops().status().get("version"));
        assertEquals("v0.2.3", ops().status().get("reset_target"));

        Fx.write(dir, ".bamfos-updater/bamfos-updater.properties", "tag=v0.2.1\n".getBytes());
        assertEquals("v0.2.1", ops().status().get("reset_target"));
    }

    /**
     * When a sync updates bcc-common.toml but defers another row, `version` keeps the new stamp (what gets
     * broadcast) while `settled` reports the ledger's older installed_version.
     */
    @Test
    void status_settledIsTheLedgersVeto_whenASyncHeldAFileBack() throws IOException {
        Fx.write(dir, "config/bcc-common.toml", "modpackVersion = \"v3\"\n".getBytes());
        Ledger led = new Ledger();
        led.installedVersion = "v2";
        led.save(Ledger.pathIn(dir));

        Map<String, Object> s = ops().status();
        assertEquals("v3", s.get("version"));
        assertEquals("v2", s.get("settled"));
    }

    /** With no stamp present, both keys fall back to the ledger so a healthy install doesn't look mismatched. */
    @Test
    void status_settledFallsBackToTheLedgerWhenBccIsAbsent() throws IOException {
        Ledger led = new Ledger();
        led.installedVersion = "v2";
        led.save(Ledger.pathIn(dir));

        Map<String, Object> s = ops().status();
        assertEquals("v2", s.get("version"));
        assertEquals("v2", s.get("settled"));
    }

    /** An empty installed_version means the updater has never managed this install, so
     *  Doors.settledVersion treats it as unmanaged instead of a mismatch (otherwise manual installs
     *  with only a bcc file would trigger a false warning). */
    @Test
    void status_handInstalledBoxAnswersOneVersionOnBothKeys() throws IOException {
        Fx.write(dir, "config/bcc-common.toml", "modpackVersion = \"v3\"\n".getBytes());

        Map<String, Object> noLedger = ops().status();
        assertEquals("v3", noLedger.get("version"));
        assertEquals("v3", noLedger.get("settled"));

        new Ledger().save(Ledger.pathIn(dir)); // a ledger file that has never recorded a finished sync
        Map<String, Object> emptyLedger = ops().status();
        assertEquals("v3", emptyLedger.get("version"));
        assertEquals("v3", emptyLedger.get("settled"));
    }

    /**
     * Parses the held flag from the same record as result and timestamp. Omit the key when no record exists
     * instead of defaulting to false.
     */
    @Test
    void status_lastSyncHeldRidesTheSameRecordRead() throws IOException {
        assertFalse(ops().status().containsKey("last_sync_held"));

        Fx.write(dir, ".bamfos-updater/last-sync.json",
                "{\"at\":1,\"tag\":\"v3\",\"result\":\"pending\",\"summary\":\"posed\",\"held\":true}".getBytes());
        Map<String, Object> held = ops().status();
        assertEquals(Boolean.TRUE, held.get("last_sync_held"));
        assertEquals("pending", held.get("last_sync_result"));

        Fx.write(dir, ".bamfos-updater/last-sync.json",
                "{\"at\":1,\"tag\":\"v3\",\"result\":\"ok\",\"summary\":\"posed\"}".getBytes());
        assertEquals(Boolean.FALSE, ops().status().get("last_sync_held"));
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
        led.files.put("mods/iris.jar", new Ledger.Entry("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
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

    /**
     * FML keeps loaded jars open, so toggling a mod on Windows hits ERROR_SHARING_VIOLATION. Defer the rename
     * through the journal for step 0 on next boot (Windows-only since POSIX renames open files).
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void setDisabled_aHeldJarIsJournaledForStepZero_neverFalseOverAnEmptyLog() throws IOException {
        byte[] jar = Fx.jar("iris");
        Fx.write(dir, "mods/iris.jar", jar);
        Ledger led = new Ledger();
        led.installedVersion = "v0.2.4";
        led.files.put("mods/iris.jar",
                new Ledger.Entry(com.retro.bamfos.updater.engine.util.HashUtils.sha256(jar)));
        led.save(Ledger.pathIn(dir));
        Path journalFile = com.retro.bamfos.updater.engine.journal.Journal.pathIn(dir);

        // Lock the jar the same way FML does: ZipFile opens without delete sharing so rename actually
        // fails on Windows (an NIO READ channel allows delete sharing so the move would succeed).
        try (var held = new java.util.zip.ZipFile(dir.resolve("mods/iris.jar").toFile())) {
            assertTrue(ops().setDisabled("mods/iris.jar", true));
            assertTrue(Files.exists(dir.resolve("mods/iris.jar")));
            var j = com.retro.bamfos.updater.engine.journal.Journal.load(journalFile);
            assertEquals(1, j.ops.size());
            assertEquals("rename", j.ops.get(0).kind());
            assertEquals("mods/iris.jar", j.ops.get(0).from());
            assertEquals("mods/iris.jar.disabled", j.ops.get(0).to());
            assertEquals("v0.2.4", j.targetVersion);
            assertFalse(Ledger.load(Ledger.pathIn(dir)).files.get("mods/iris.jar").disabled);

            assertTrue(ops().setDisabled("mods/iris.jar", true));
            assertEquals(1, com.retro.bamfos.updater.engine.journal.Journal.load(journalFile).ops.size());
        }

        // Lock is released, matching step 0 on next boot before FML opens any jars.
        var replayed = com.retro.bamfos.updater.engine.journal.Journal.load(journalFile).replay(dir);
        assertTrue(replayed.allLanded());
        assertTrue(Files.exists(dir.resolve("mods/iris.jar.disabled")));
        assertFalse(Files.exists(dir.resolve("mods/iris.jar")));
    }

    /**
     * Return true if the rename succeeded even when Ledger.save fails; the next sync heals the ledger from disk.
     */
    @Test
    void setDisabled_aLedgerThatWillNotSaveNeverWalksBackALandedFlip() throws IOException {
        Fx.write(dir, "mods/iris.jar", Fx.jar("iris"));
        Ledger led = new Ledger();
        led.files.put("mods/iris.jar",
                new Ledger.Entry("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        led.save(Ledger.pathIn(dir));
        // Putting a directory at the tmp path makes save() fail after the move succeeds.
        Files.createDirectories(dir.resolve(".bamfos-updater").resolve("ledger.json.tmp"));

        assertTrue(ops().setDisabled("mods/iris.jar", true));
        assertTrue(Files.exists(dir.resolve("mods/iris.jar.disabled")));
        assertFalse(Files.exists(dir.resolve("mods/iris.jar")));
        assertFalse(Ledger.load(Ledger.pathIn(dir)).files.get("mods/iris.jar").disabled);
    }

    /** Log a clear reason on every toggle refusal and leave no journal behind to fail on future boots. */
    @Test
    void setDisabled_aRefusalPutsItsReasonInTheLog_andJournalsNothing() throws IOException {
        Fx.write(dir, "mods/iris.jar", Fx.jar("iris"));
        Fx.write(dir, "mods/iris.jar.disabled", Fx.jar("iris-twin"));
        java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
        java.io.PrintStream real = System.out;
        System.setOut(new java.io.PrintStream(sink));
        try {
            assertFalse(ops().setDisabled("mods/iris.jar", true));
            assertFalse(ops().setDisabled("mods/ghost.jar", true));
        } finally {
            System.setOut(real);
        }
        String log = sink.toString();
        assertTrue(log.contains("mods/iris.jar") && log.contains("both names are on disk"), log);
        assertTrue(log.contains("mods/ghost.jar") && log.contains("neither name is on disk"), log);
        assertTrue(Files.exists(dir.resolve("mods/iris.jar"))
                        && Files.exists(dir.resolve("mods/iris.jar.disabled")));
        assertFalse(Files.exists(com.retro.bamfos.updater.engine.journal.Journal.pathIn(dir)));
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
     * A failed config swap (simulated with a blocking directory) must keep the old properties and delete the .tmp.
     */
    @Test
    void writeKeys_aFailedSwapLeavesTheOldConfigAndNoTmpLitter() throws IOException {
        Path p = dir.resolve(".bamfos-updater").resolve("bamfos-updater.properties");
        Files.createDirectories(p.resolve("squatter")); // p is a non-empty directory so the move should fail

        assertThrows(IOException.class, () -> ops().writePin("v0.2.2"));

        assertFalse(Files.exists(p.resolveSibling(p.getFileName() + ".tmp")));
        assertTrue(Files.isDirectory(p.resolve("squatter")));
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

    /**
     * Server `/bamfos sync` targets the pin or literal "latest" (matching the pre-JVM door), never client-style
     * ping resolution. Verified via the GitHub URL requested.
     */
    @Test
    void serverSyncNow_asksForLatest_orForThePinWhenThereIsOne() throws IOException {
        java.util.List<String> asked = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        com.sun.net.httpserver.HttpServer gh = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        gh.createContext("/repos/o/r/releases/", ex -> {
            asked.add(ex.getRequestURI().getPath());
            ex.sendResponseHeaders(404, -1); // no such release, whichever one was asked for
            ex.close();
        });
        gh.start();
        try {
            String api = "http://127.0.0.1:" + gh.getAddress().getPort();
            Fx.write(dir, ".bamfos-updater/bamfos-updater.properties", "repo=o/r\ngithub_token=tok\n".getBytes());

            assertEquals(1, new UpdaterOps(dir, api).serverSyncNow());
            assertEquals("/repos/o/r/releases/latest", asked.get(0));

            Fx.write(dir, ".bamfos-updater/bamfos-updater.properties",
                    "repo=o/r\ngithub_token=tok\ntag=v0.2.2\n".getBytes());
            assertEquals(1, new UpdaterOps(dir, api).serverSyncNow());
            assertEquals("/repos/o/r/releases/tags/v0.2.2", asked.get(1));
        } finally {
            gh.stop(0);
        }
    }

    /**
     * Verifies `/bamfos sync` targets side=server and writes server-sync.log. Jars stay staged in the journal
     * until next boot because FML keeps mods/ open.
     */
    @Test
    void serverSyncNow_syncsTheServerSide_andStagesItForTheNextBoot() throws IOException {
        byte[] jar = Fx.jar("srv");
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("path", "mods/serveronly.jar");
        entry.put("sha256", com.retro.bamfos.updater.engine.util.HashUtils.sha256(jar));
        entry.put("size", (long) jar.length);
        entry.put("side", "server");
        entry.put("policy", "managed");
        entry.put("source", Map.of("type", "release-asset", "asset", "serveronly.jar"));
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("schema_version", 1L);
        m.put("pack", new java.util.LinkedHashMap<>(Map.of("id", "bamfos", "version", "vS",
                "mc", "1.21.1", "neoforge", "21.1.235", "updater_abi", 1L)));
        m.put("requires_relaunch", false);
        m.put("removed_modids", List.of());
        m.put("files", List.of(entry));
        byte[] manifest = com.retro.bamfos.updater.engine.json.JsonWriter.write(m)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        com.sun.net.httpserver.HttpServer gh = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        gh.createContext("/repos/o/r/releases/latest", ex -> body(ex,
                ("{\"tag_name\":\"vS\",\"assets\":[{\"name\":\"manifest.json\",\"id\":1},"
                        + "{\"name\":\"serveronly.jar\",\"id\":2}]}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        gh.createContext("/repos/o/r/releases/assets/", ex -> body(ex,
                ex.getRequestURI().getPath().endsWith("/1") ? manifest : jar));
        gh.start();
        try {
            Fx.write(dir, ".bamfos-updater/bamfos-updater.properties", "repo=o/r\ngithub_token=tok\n".getBytes());
            assertEquals(0, new UpdaterOps(dir, "http://127.0.0.1:" + gh.getAddress().getPort()).serverSyncNow());

            Path serverLog = dir.resolve(".bamfos-updater").resolve("server-sync.log");
            assertTrue(Files.isRegularFile(serverLog));
            String log = Files.readString(serverLog);
            assertTrue(log.contains("server synced to vS"), String.valueOf(log));
            assertTrue(log.contains("staged files apply on the next boot"), log);
            assertFalse(Files.exists(dir.resolve("mods/serveronly.jar")));
            assertTrue(Files.isRegularFile(
                            com.retro.bamfos.updater.engine.journal.Journal.pathIn(dir)));
        } finally {
            gh.stop(0);
        }
    }

    /**
     * Staged sync returns 6 when already current and 0 when changes were actually staged, so the BIOS only
     * prompts a reboot when there is work to apply.
     */
    @Test
    void aSyncThatStagesNothingAnswersAlreadyCurrent_notChangesStaged() throws IOException {
        byte[] cfgBytes = "the bytes this release ships\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = com.retro.bamfos.updater.engine.util.HashUtils.sha256(cfgBytes);
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("path", "config/current.toml");
        entry.put("sha256", sha);
        entry.put("size", (long) cfgBytes.length);
        entry.put("side", "both");
        entry.put("policy", "managed");
        entry.put("source", Map.of("type", "release-asset", "asset", "current.toml"));
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("schema_version", 1L);
        m.put("pack", new java.util.LinkedHashMap<>(Map.of("id", "bamfos", "version", "v1",
                "mc", "1.21.1", "neoforge", "21.1.235", "updater_abi", 1L)));
        m.put("requires_relaunch", false);
        m.put("removed_modids", List.of());
        m.put("files", List.of(entry));
        byte[] manifest = com.retro.bamfos.updater.engine.json.JsonWriter.write(m)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        com.sun.net.httpserver.HttpServer gh = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        gh.createContext("/repos/o/r/releases/tags/v1", ex -> body(ex,
                ("{\"tag_name\":\"v1\",\"assets\":[{\"name\":\"manifest.json\",\"id\":1},"
                        + "{\"name\":\"current.toml\",\"id\":2}]}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        gh.createContext("/repos/o/r/releases/assets/", ex -> body(ex,
                ex.getRequestURI().getPath().endsWith("/1") ? manifest : cfgBytes));
        gh.start();
        try {
            Fx.write(dir, ".bamfos-updater/bamfos-updater.properties", "repo=o/r\ngithub_token=tok\n".getBytes());
            Fx.write(dir, "config/current.toml", cfgBytes);
            Ledger led = new Ledger();
            led.installedVersion = "v1";
            led.files.put("config/current.toml", new Ledger.Entry(sha));
            led.save(Ledger.pathIn(dir));
            UpdaterOps ops = new UpdaterOps(dir, "http://127.0.0.1:" + gh.getAddress().getPort());

            assertEquals(6, ops.syncTo("v1"));

            Files.delete(dir.resolve("config/current.toml")); // the day an antivirus eats a managed file
            assertEquals(0, ops.syncTo("v1"));
        } finally {
            gh.stop(0);
        }
    }

    /** Server sync returns 6 when already current, matching the client and OpsBrain so the watchdog won't error. */
    @Test
    void serverSyncNow_answersAlreadyCurrentAsSix_nowThatOpsBrainSpeaksIt() throws IOException {
        byte[] jar = Fx.jar("srv");
        String sha = com.retro.bamfos.updater.engine.util.HashUtils.sha256(jar);
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("path", "mods/serveronly.jar");
        entry.put("sha256", sha);
        entry.put("size", (long) jar.length);
        entry.put("side", "server");
        entry.put("policy", "managed");
        entry.put("source", Map.of("type", "release-asset", "asset", "serveronly.jar"));
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("schema_version", 1L);
        m.put("pack", new java.util.LinkedHashMap<>(Map.of("id", "bamfos", "version", "vS",
                "mc", "1.21.1", "neoforge", "21.1.235", "updater_abi", 1L)));
        m.put("requires_relaunch", false);
        m.put("removed_modids", List.of());
        m.put("files", List.of(entry));
        byte[] manifest = com.retro.bamfos.updater.engine.json.JsonWriter.write(m)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        com.sun.net.httpserver.HttpServer gh = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        gh.createContext("/repos/o/r/releases/latest", ex -> body(ex,
                "{\"tag_name\":\"vS\",\"assets\":[{\"name\":\"manifest.json\",\"id\":1}]}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        gh.createContext("/repos/o/r/releases/assets/", ex -> body(ex, manifest));
        gh.start();
        try {
            Fx.write(dir, ".bamfos-updater/bamfos-updater.properties", "repo=o/r\ngithub_token=tok\n".getBytes());
            Fx.write(dir, "mods/serveronly.jar", jar);
            Ledger led = new Ledger();
            led.installedVersion = "vS";
            led.files.put("mods/serveronly.jar", new Ledger.Entry(sha));
            led.save(Ledger.pathIn(dir));

            assertEquals(6, new UpdaterOps(dir, "http://127.0.0.1:" + gh.getAddress().getPort()).serverSyncNow());
        } finally {
            gh.stop(0);
        }
    }

    /**
     * `/bamfos sync` runs the mod's bundled engine rather than the promoted one, so it must NOT clear engine
     * probation or EngineGuard won't roll back a broken promoted engine on next boot (ServerDoorTest covers
     * real door clearance).
     */
    @Test
    void serverSyncNow_leavesEngineProbationArmed_cuzTheWatchdogIsNotThePromotedEngine() throws IOException {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("schema_version", 1L);
        m.put("pack", new java.util.LinkedHashMap<>(Map.of("id", "bamfos", "version", "vP",
                "mc", "1.21.1", "neoforge", "21.1.235", "updater_abi", 1L)));
        m.put("requires_relaunch", false);
        m.put("removed_modids", List.of());
        m.put("files", List.of()); // Nothing to move; the flag is cleared during parse, not apply.
        byte[] manifest = com.retro.bamfos.updater.engine.json.JsonWriter.write(m)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        com.sun.net.httpserver.HttpServer gh = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        gh.createContext("/repos/o/r/releases/latest", ex -> body(ex,
                "{\"tag_name\":\"vP\",\"assets\":[{\"name\":\"manifest.json\",\"id\":1}]}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        gh.createContext("/repos/o/r/releases/assets/", ex -> body(ex, manifest));
        gh.start();
        try {
            Fx.write(dir, ".bamfos-updater/bamfos-updater.properties", "repo=o/r\ngithub_token=tok\n".getBytes());
            Path probation = dir.resolve(".bamfos-updater").resolve("engine").resolve("engine-probation");
            Files.createDirectories(probation.getParent());
            Files.writeString(probation, "a promoted sha the shim wrote");

            int code = new UpdaterOps(dir, "http://127.0.0.1:" + gh.getAddress().getPort()).serverSyncNow();
            assertTrue(code == 0 || code == 6, String.valueOf(code));
            assertTrue(Files.isRegularFile(probation));
        } finally {
            gh.stop(0);
        }
    }

    private static void body(com.sun.net.httpserver.HttpExchange ex, byte[] b) throws IOException {
        ex.sendResponseHeaders(200, b.length);
        try (java.io.OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    /** Return code 1 ("no target") on a 404 tag instead of code 2 ("sync failed"), since the release isn't up. */
    @Test
    void aMissingReleaseIsNoTargetToSyncTo_notAFailedSync() throws IOException {
        com.sun.net.httpserver.HttpServer gh = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        gh.createContext("/repos/o/r/releases/tags/v9.9", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        gh.start();
        try {
            Fx.write(dir, ".bamfos-updater/bamfos-updater.properties", "repo=o/r\ngithub_token=tok\n".getBytes());
            assertEquals(1, new UpdaterOps(dir, "http://127.0.0.1:" + gh.getAddress().getPort()).syncTo("v9.9"));
        } finally {
            gh.stop(0);
        }
    }

    /**
     * Lets an operator clear the dead-token marker after fixing the token instead of waiting for a manifest
     * parse; returns 1 when no marker was present.
     */
    @Test
    void clearTokenDead_retiresTheMarker_andReportsAnHonestNoOp() throws IOException {
        assertEquals(1, ops().clearTokenDead());

        Fx.write(dir, ".bamfos-updater/token-dead", "401".getBytes());
        assertEquals(Boolean.TRUE, ops().status().get("token_dead"));
        assertEquals(0, ops().clearTokenDead());
        assertFalse(Files.exists(dir.resolve(".bamfos-updater").resolve("token-dead")));
        assertEquals(Boolean.FALSE, ops().status().get("token_dead"));
        assertEquals(1, ops().clearTokenDead());
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
        bus.ident("o/r");
        bus.channel("idle");
        bus.syncBegin("o/r", "v1", "v2");
        bus.fileDone("weird\tname.jar");
        bus.note("PACK UPDATE v2, the headline");
        bus.manifest(270);
        bus.plan(12);
        bus.fileStart("cm.jar");
        bus.applying();
        bus.synced("v1", "v2", true, 3);
        bus.end("ok");

        assertEquals(List.of(
                "LOG\tupdater: packages current (v0.2.4), nothing to do",
                "IDENT\to/r", // Channel name only, since IDENT runs before we query GitHub.
                "CHANNEL\tidle", // Status indicator is sent as a separate event once the updater finishes checking.
                "SYNC\to/r\tv1\tv2",
                "FDONE\tweird name.jar", // Tabs inside the payload shouldn't break field parsing.
                "NOTE\tPACK UPDATE v2, the headline",
                "MANIFEST\t270",
                "PLAN\t12",
                "FILE\tcm.jar",
                "PHASE\tApplying",
                "SYNCED\tv1\tv2\t3", // Applied count goes in the 4th field so the final summary doesn't just show the planned count.
                "PENDING",
                "END\tok"), Files.readAllLines(dir.resolve(".bamfos-updater").resolve("bus.log")));
        // a new boot truncates
        BootBus.at(dir);
        assertEquals(0, Files.size(dir.resolve(".bamfos-updater").resolve("bus.log")));
    }
}
