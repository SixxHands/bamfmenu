package com.retro.bamfos.updater.engine.ping;

import com.retro.bamfos.updater.engine.json.MiniJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** Tests formatting and wire payload of the maintenance notice; socket handling is in PortHoldTest. */
class UpdatingNoticeTest {

    @TempDir
    Path dir;

    @Test
    void namesTheReleaseTheOwnersWay() {
        assertEquals("Updating the server to v0.2.6!", UpdatingNotice.headline("v0.2.6"));
    }

    /**
     * With no target version, use neutral wording instead of claiming an update, since ServerDoor opens this
     * notice on ordinary restarts and crash loops too.
     */
    @Test
    void doesNotClaimAnUpdateWhenNothingOnDiskNamesOne() {
        assertEquals("Starting server...", UpdatingNotice.headline(""));
        assertEquals("Starting server...\nBack in a minute", UpdatingNotice.motd(""));

        Map<?, ?> root = (Map<?, ?>) MiniJson.parse(UpdatingNotice.statusJson("", null));
        String drawn = String.valueOf(((Map<?, ?>) root.get("description")).get("text"));
        assertFalse(drawn.toLowerCase().contains("updating"), String.valueOf(drawn));
        Map<?, ?> refusal = (Map<?, ?>) MiniJson.parse(UpdatingNotice.refusalJson(""));
        assertFalse(String.valueOf(refusal.get("text")).toLowerCase().contains("updating"));
    }

    /**
     * Suppress the target tag when last_target matches the installed version, or every ordinary restart would
     * keep advertising an update that already finished.
     */
    @Test
    void saysNothingWhenTheBoxIsAlreadyOnThatRelease() {
        assertEquals("v0.2.6", UpdatingNotice.target("v0.2.6", "v0.2.5"));
        assertEquals("", UpdatingNotice.target("v0.2.6", "v0.2.6"));
        assertEquals("", UpdatingNotice.target("", ""));
    }

    /** Watchdog writes this file and engine reads it, so verify the format here too using the
     *  exact output of WatchdogState.save. */
    @Test
    void readsTheTargetTheWatchdogWroteBeforeItHalted() throws IOException {
        Path f = dir.resolve(".bamfos-updater").resolve("watchdog-state.properties");
        Files.createDirectories(f.getParent());
        Files.writeString(f, "last_target=v0.2.6\nlast_restart_at=1753500000000\nlast_target_attempts=1\n");
        assertEquals("v0.2.6", UpdatingNotice.lastTarget(dir));
    }

    @Test
    void readsNoTargetOffABoxThatNeverRanTheWatchdog() {
        assertEquals("", UpdatingNotice.lastTarget(dir));
    }

    /** Protocol version must match the client, otherwise the server list shows INCOMPATIBLE
     *  during maintenance. Uses the same version pair as Slp. */
    @Test
    void reportsTheOwnersLineAndTheProtocolTheClientRuns() {
        Map<?, ?> root = (Map<?, ?>) MiniJson.parse(UpdatingNotice.statusJson("v0.2.6", null));
        Map<?, ?> description = (Map<?, ?>) root.get("description");
        assertEquals("Updating the server to v0.2.6!\nBack in a minute", description.get("text"));
        Map<?, ?> version = (Map<?, ?>) root.get("version");
        assertEquals((long) Slp.PROTOCOL, version.get("protocol"));
        assertEquals(Slp.MC_VERSION, version.get("name"));
    }

    /** Omit the players object so vanilla shows grey "???" instead of 0/20 while the server
     *  isn't joinable. */
    @Test
    void reportsNoPlayerCountBecauseItHasNone() {
        Map<?, ?> root = (Map<?, ?>) MiniJson.parse(UpdatingNotice.statusJson("v0.2.6", null));
        assertFalse(root.containsKey("players"), root.keySet().toString());
    }

    /**
     * Never include betterStatus in the notice response, or clients pinging mid-update would try to sync to a
     * release the server hasn't finished applying yet.
     */
    @Test
    void advertisesNoPackVersionSoNoClientSyncsToOne() {
        String json = UpdatingNotice.statusJson("v0.2.6", null);
        assertFalse(json.contains("betterStatus"), json);
        assertTrue(BetterStatus.parse(json).isEmpty());
    }

    /** Include server-icon.png when present so the server list row keeps the usual icon during maintenance. */
    @Test
    void carriesTheServerIconWhenThereIsOne() throws IOException {
        Files.write(dir.resolve("server-icon.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        Map<?, ?> root = (Map<?, ?>) MiniJson.parse(
                UpdatingNotice.statusJson("v0.2.6", UpdatingNotice.icon(dir)));
        assertEquals("data:image/png;base64,iVBORw==", root.get("favicon"));
    }

    /** Skip oversized icon files so a huge image doesn't blow up the status ping response. */
    @Test
    void leavesAnAbsurdIconOnDisk() throws IOException {
        Files.write(dir.resolve("server-icon.png"), new byte[UpdatingNotice.MAX_ICON_BYTES + 1]);
        assertNull(UpdatingNotice.icon(dir));
    }

    @Test
    void tellsHimWhenToComeBack() {
        Map<?, ?> c = (Map<?, ?>) MiniJson.parse(UpdatingNotice.refusalJson("v0.2.6"));
        assertEquals("Updating the server to v0.2.6!\nGive it a minute and try again.", c.get("text"));
    }

    /**
     * Read the bind port from server.properties, never bamfos-updater.properties (that's the client ping target).
     */
    @Test
    void listensWhereMinecraftListens() {
        Properties p = new Properties();
        p.setProperty("server-port", "25577");
        p.setProperty("server-ip", "127.0.0.1");
        assertEquals(25577, UpdatingNotice.bindPort(p));
        assertEquals("127.0.0.1", UpdatingNotice.bindHost(p));
        assertEquals(25565, UpdatingNotice.bindPort(new Properties()));
        assertEquals("", UpdatingNotice.bindHost(new Properties()));
    }

    /** A malformed or out-of-range port in server.properties must fall back safely without throwing on boot. */
    @Test
    void survivesAHandEditedPort() {
        Properties junk = new Properties();
        junk.setProperty("server-port", "twenty five thousand");
        assertEquals(25565, UpdatingNotice.bindPort(junk));
        Properties huge = new Properties();
        huge.setProperty("server-port", "99999");
        assertEquals(25565, UpdatingNotice.bindPort(huge));
    }
}
