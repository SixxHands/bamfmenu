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

    /** Emit quarantined jars under the integrity subsystem with one shared reason line per batch so
     * multi-jar kicks do not overflow the boot terminal. */
    @Test
    void quarantineBlock_isOneRowPerJarAndOneReasonForTheBatch() throws IOException {
        Fx.write(dir, "mods/chipped-x.jar", Fx.jar("chippedx"));
        Fx.write(dir, "mods/envelope-y.jar", Fx.jar("envelopey"));
        breadcrumb("Channel of mod x failed: chippedx:main and envelopey:main missing on the server side");

        KickGuard.consume(dir, List.of(), BootBus.at(dir));

        List<String> bus = Files.readAllLines(dir.resolve(".bamfos-updater").resolve("bus.log"));
        assertEquals(2, bus.stream().filter(l -> l.startsWith("LOG\tintegrity: quarantined ")).count(), String.valueOf(bus));
        assertEquals(1, bus.stream().filter(l -> l.equals("LOG\treason: server integrity policy")).count(), String.valueOf(bus));
        assertTrue(bus.indexOf("LOG\treason: server integrity policy") == bus.size() - 1, String.valueOf(bus));
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
    void disabledTwinExists_leavesJarAndConsumesBreadcrumb() throws IOException {
        // A .disabled file with the same name already exists (from the user), so don't overwrite it.
        // Leave the culprit in place for the duplicate guard to report instead.
        Fx.write(dir, "mods/chipped-x.jar", Fx.jar("chippedx"));
        Fx.write(dir, "mods/chipped-x.jar.disabled", "a user's own file".getBytes());
        breadcrumb("Channel chippedx:main is missing on the server side");

        var out = KickGuard.consume(dir, List.of(), BootBus.headless()).orElseThrow();
        assertTrue(out.quarantined().isEmpty());
        assertTrue(Files.exists(dir.resolve("mods/chipped-x.jar")));
        assertFalse(Ledger.load(Ledger.pathIn(dir)).files.containsKey("mods/chipped-x.jar"));
        assertFalse(Files.exists(Breadcrumb.pathIn(dir)));
    }

    /** The reason the mod half writes is NeoForge's own prose wrapped around the channel dump, so mod,
     *  server, client and channel are all whole words in it. A player's hand-added jar whose modid happens
     *  to be one of them must not go dark, and nothing in the pack ever re-enables it if it does. */
    @Test
    void neoforgeHeaderWords_neverQuarantineAModNamedAfterThem() throws IOException {
        Fx.write(dir, "mods/server-utils.jar", Fx.jar("server"));
        Fx.write(dir, "mods/chipped-x.jar", Fx.jar("chippedx"));
        breadcrumb("Channel of mod x failed to connect: chippedx:main is missing on the server side, "
                + "but the client requires it");

        var out = KickGuard.consume(dir, List.of(), BootBus.headless()).orElseThrow();
        assertEquals(List.of("mods/chipped-x.jar"), out.quarantined());
        assertTrue(Files.exists(dir.resolve("mods/server-utils.jar")));
        assertFalse(Files.exists(dir.resolve("mods/server-utils.jar.disabled")));
    }

    /** Proxy kick messages often include a URL, and https:// looks like a channel id with "https"
     *  as the namespace. Filter out URL schemes before checking for channels so a link doesn't hide the actual mod. */
    @Test
    void urlInTheReason_doesNotSuppressAttribution() throws IOException {
        Fx.write(dir, "mods/chipped-x.jar", Fx.jar("chippedx"));
        breadcrumb("You were kicked: chippedx is not allowed here, see https://wiki.example/rules");

        var out = KickGuard.consume(dir, List.of(), BootBus.headless()).orElseThrow();
        assertEquals(List.of("mods/chipped-x.jar"), out.quarantined());
    }

    /** Ignore host:port addresses in proxy kick text; they match the namespace:path pattern and would
     * hide the actual culprit mod. */
    @Test
    void hostAndPortInTheReason_doesNotSuppressAttribution() throws IOException {
        Fx.write(dir, "mods/chipped-x.jar", Fx.jar("chippedx"));
        breadcrumb("Incompatible client, remove chippedx and reconnect to play.example.net:25565");

        var out = KickGuard.consume(dir, List.of(), BootBus.headless()).orElseThrow();
        assertEquals(List.of("mods/chipped-x.jar"), out.quarantined());
    }

    /** Don't emit contradictory status lines. When an existing .disabled file blocks the move, the
     *  per-jar line already explains what happened, so don't follow it with a summary saying no
     *  untracked modules matched. Same applies if AV locks the jar. */
    @Test
    void aNamedJarWeCouldNotMove_neverPrintsTheNamedNothingLine() throws IOException {
        Fx.write(dir, "mods/chipped-x.jar", Fx.jar("chippedx"));
        Fx.write(dir, "mods/chipped-x.jar.disabled", "a user's own file".getBytes());
        breadcrumb("Channel chippedx:main is missing on the server side");

        KickGuard.consume(dir, List.of(), BootBus.at(dir));

        List<String> bus = Files.readAllLines(dir.resolve(".bamfos-updater").resolve("bus.log"));
        assertTrue(bus.stream().anyMatch(l -> l.contains("cannot quarantine mods/chipped-x.jar")), String.valueOf(bus));
        assertTrue(bus.stream().noneMatch(l -> l.contains("named no untracked modules")), String.valueOf(bus));
    }

    /** The control for the row above: a reason that really named nothing on disk still says so, or the quiet
     *  boot loses the only line that tells the player Q2 looked and found nothing of his. */
    @Test
    void aReasonNamingNothingOnDisk_stillSaysSo() throws IOException {
        Fx.write(dir, "mods/innocent.jar", Fx.jar("innocent"));
        breadcrumb("Channel chippedx:main is missing on the server side");

        KickGuard.consume(dir, List.of(), BootBus.at(dir));

        List<String> bus = Files.readAllLines(dir.resolve(".bamfos-updater").resolve("bus.log"));
        assertTrue(bus.stream().anyMatch(l -> l.contains("named no untracked modules")), bus.toString());
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
