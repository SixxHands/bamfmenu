package com.retro.bamfos.updater.engine.guard;

import com.retro.bamfos.updater.engine.Fx;
import com.retro.bamfos.updater.engine.bus.BootBus;
import com.retro.bamfos.updater.engine.ledger.Ledger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Q2, engine half: quarantine EXACTLY the untracked culprits the kick reason names. */
class KickGuardTest {

    @TempDir
    Path dir;

    private void breadcrumb(String reason) throws IOException {
        Path p = Breadcrumb.pathIn(dir);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "{\"server\":\"1.2.3.4\",\"reason\":\"" + reason
                + "\",\"fingerprint\":\"abcd1234\",\"at\":1}");
    }

    @Test
    void namedUntrackedJar_isQuarantined_withReason_onceOnly() throws IOException {
        Fx.write(dir, "mods/chipped-x.jar", Fx.jar("chippedx"));
        Fx.write(dir, "mods/innocent.jar", Fx.jar("innocent"));
        // channel-id form: the colon splits ns from path, so the namespace is a plain token
        breadcrumb("Channel of mod x failed: chippedx:main is missing on the server side");

        var out = KickGuard.consume(dir, List.of(), BootBus.headless()).orElseThrow();
        assertEquals("abcd1234", out.fingerprint());
        assertEquals(List.of("mods/chipped-x.jar"), out.quarantined());
        assertFalse(Files.exists(dir.resolve("mods/chipped-x.jar")));
        assertTrue(Files.exists(dir.resolve("mods/chipped-x.jar.disabled")));
        assertTrue(Files.exists(dir.resolve("mods/innocent.jar")));
        assertFalse(Files.exists(Breadcrumb.pathIn(dir)));

        Ledger led = Ledger.load(Ledger.pathIn(dir));
        var en = led.files.get("mods/chipped-x.jar");
        assertTrue(en.quarantined);
        assertTrue(en.quarantineReason.contains("chippedx"));

        // Same kick again: the jar is already quarantined in the ledger, so leave it alone.
        breadcrumb("Channel of mod x failed: chippedx:main is missing on the server side");
        Files.move(dir.resolve("mods/chipped-x.jar.disabled"), dir.resolve("mods/chipped-x.jar")); // user re-enabled
        var again = KickGuard.consume(dir, List.of(), BootBus.headless()).orElseThrow();
        assertTrue(again.quarantined().isEmpty());
        assertTrue(Files.exists(dir.resolve("mods/chipped-x.jar")));
    }

    @Test
    void managedAndPinnedJars_areNeverTargets() throws IOException {
        Fx.write(dir, "mods/managed.jar", Fx.jar("managedmod"));
        Fx.write(dir, "mods/pinned.jar", Fx.jar("pinnedmod"));
        Ledger led = new Ledger();
        led.files.put("mods/managed.jar", new Ledger.Entry("aa"));
        led.save(Ledger.pathIn(dir));
        breadcrumb("managedmod and pinnedmod broke the join");

        var out = KickGuard.consume(dir, List.of("mods/pinned.jar"), BootBus.headless()).orElseThrow();
        assertTrue(out.quarantined().isEmpty());
        assertTrue(Files.exists(dir.resolve("mods/managed.jar")));
        assertTrue(Files.exists(dir.resolve("mods/pinned.jar")));
    }

    @Test
    void unparseableReason_quarantinesNothing_butStillConsumes() throws IOException {
        Fx.write(dir, "mods/some.jar", Fx.jar("somemod"));
        breadcrumb("&)(*#@ 8823 !!");
        var out = KickGuard.consume(dir, List.of(), BootBus.headless()).orElseThrow();
        assertTrue(out.quarantined().isEmpty());
        assertEquals("none", out.action());
        assertTrue(Files.exists(dir.resolve("mods/some.jar")));
        assertFalse(Files.exists(Breadcrumb.pathIn(dir)));
    }

    @Test
    void noBreadcrumb_isEmpty() throws IOException {
        assertTrue(KickGuard.consume(dir, List.of(), BootBus.headless()).isEmpty());
    }

    @Test
    void historyRoundTrips() throws IOException {
        DisconnectHistory h = new DisconnectHistory();
        h.record("fp1", "none", false, 111);
        h.record("fp1", "quarantined mods/x.jar", true, 222);
        h.save(dir);
        DisconnectHistory back = DisconnectHistory.load(dir);
        var e = back.entries.get("fp1");
        assertEquals(2, e.count);
        assertEquals(222, e.lastAt);
        assertEquals("quarantined mods/x.jar", e.lastAction);
        assertTrue(e.versionChanged);
    }
}
