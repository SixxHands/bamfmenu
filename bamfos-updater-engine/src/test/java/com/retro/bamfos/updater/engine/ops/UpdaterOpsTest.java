package com.retro.bamfos.updater.engine.ops;

import com.retro.bamfos.updater.engine.Fx;
import com.retro.bamfos.updater.engine.bus.BootBus;
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

    @Test
    void bootBus_framesEventsAndStripsTabs() throws IOException {
        BootBus bus = BootBus.at(dir);
        bus.line("updater: pack current (v0.2.4), nothing to do");
        bus.syncBegin("o/r", "v1", "v2");
        bus.fileDone("weird\tname.jar");
        List<String> lines = Files.readAllLines(dir.resolve(".bamfos-updater").resolve("bus.log"));
        assertEquals("LOG\tupdater: pack current (v0.2.4), nothing to do", lines.get(0));
        assertEquals("SYNC\to/r\tv1\tv2", lines.get(1));
        assertEquals("FDONE\tweird name.jar", lines.get(2));
        // a new boot truncates
        BootBus.at(dir);
        assertEquals(0, Files.size(dir.resolve(".bamfos-updater").resolve("bus.log")));
    }
}
