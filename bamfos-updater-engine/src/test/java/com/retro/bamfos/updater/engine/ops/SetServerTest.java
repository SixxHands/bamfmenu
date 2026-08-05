package com.retro.bamfos.updater.engine.ops;

import com.retro.bamfos.updater.engine.config.UpdaterConfig;
import com.retro.bamfos.updater.engine.ping.Nbt;
import com.retro.bamfos.updater.engine.ping.ServersDat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * setServer updates both the updater's server address and servers.dat. These tests mainly guard servers.dat
 * so we never wipe or alter the player's other saved servers.
 */
class SetServerTest {

    private static Path props(Path dir) {
        return dir.resolve(".bamfos-updater").resolve("bamfos-updater.properties");
    }

    /** Builds a servers.dat with the pack entry followed by a player's own server. */
    private static void writeList(Path serversDat, String ourName, String ourIp, String theirName,
                                  String theirIp) throws IOException {
        LinkedHashMap<String, Nbt.Val> ours = new LinkedHashMap<>();
        ours.put("name", new Nbt.Val(8, ourName));
        ours.put("ip", new Nbt.Val(8, ourIp));
        LinkedHashMap<String, Nbt.Val> theirs = new LinkedHashMap<>();
        theirs.put("name", new Nbt.Val(8, theirName));
        theirs.put("ip", new Nbt.Val(8, theirIp));
        LinkedHashMap<String, Nbt.Val> root = new LinkedHashMap<>();
        root.put("servers", new Nbt.Val(9, new Nbt.ListVal(10,
                List.of(new Nbt.Val(10, ours), new Nbt.Val(10, theirs)))));
        Files.write(serversDat, Nbt.write(new Nbt.Root("", root)));
    }

    @Test
    void bothHalvesLand_andTheirOwnServersAreUntouched() throws IOException {
        Path dir = Files.createTempDirectory("setserver");
        Files.createDirectories(props(dir).getParent());
        Files.writeString(props(dir), "repo=SixxHands/bamfos-pack\nserver_host=1.1.1.1\nserver_port=25565\n");
        writeList(dir.resolve("servers.dat"), "BAMFOS", "1.1.1.1", "Jax realm", "play.jax.example:19132");

        assertEquals(0, new UpdaterOps(dir, null).setServer("2.2.2.2", 25599));

        UpdaterConfig after = UpdaterConfig.load(dir);
        assertEquals("2.2.2.2", after.serverHost());
        assertEquals(25599, after.serverPort());
        assertEquals("2.2.2.2:25599", ServersDat.entryIp(dir.resolve("servers.dat"), "BAMFOS").orElse(""));
        assertEquals("play.jax.example:19132",
                ServersDat.entryIp(dir.resolve("servers.dat"), "Jax realm").orElse(""));
    }

    /**
     * Writes default port 25565 to properties without appending :25565 to servers.dat, so a bare hostname
     * stays clean in the player's server list.
     */
    @Test
    void theDefaultPortIsNotPaintedOntoTheirAddress() throws IOException {
        Path dir = Files.createTempDirectory("setserver");
        Files.createDirectories(props(dir).getParent());
        Files.writeString(props(dir), "server_host=1.1.1.1\n");
        writeList(dir.resolve("servers.dat"), "BAMFOS", "1.1.1.1", "other", "o:1");

        assertEquals(0, new UpdaterOps(dir, null).setServer("bamf.example.com", 25565));
        assertEquals("bamf.example.com",
                ServersDat.entryIp(dir.resolve("servers.dat"), "BAMFOS").orElse(""));
        assertEquals(25565, UpdaterConfig.load(dir).serverPort());
    }

    /** Merge overlays all fields, so carry over the player's custom server name instead of resetting it. */
    @Test
    void aPlayerRenamedEntryKeepsItsName() throws IOException {
        Path dir = Files.createTempDirectory("setserver");
        Files.createDirectories(props(dir).getParent());
        Files.writeString(props(dir), "server_host=1.1.1.1\n");
        // No ledger present, so merge falls back to matching "BAMFOS".
        writeList(dir.resolve("servers.dat"), "BAMFOS", "1.1.1.1", "other", "o:1");

        new UpdaterOps(dir, null).setServer("3.3.3.3", 25565);
        assertEquals("3.3.3.3", ServersDat.entryIp(dir.resolve("servers.dat"), "BAMFOS").orElse(""));
        assertTrue(ServersDat.entryIp(dir.resolve("servers.dat"), "other").isPresent());
    }

    /**
     * Looks up the pack entry using the merge name stored in the ledger rather than falling back to "BAMFOS",
     * which would leave the renamed row on the old address and add a duplicate entry.
     */
    @Test
    void theLedgersRecordedNameFindsTheRenamedEntry() throws IOException {
        Path dir = Files.createTempDirectory("setserver");
        Files.createDirectories(props(dir).getParent());
        Files.writeString(props(dir), "server_host=1.1.1.1\n");
        writeList(dir.resolve("servers.dat"), "My BAMFOS", "1.1.1.1", "other", "o:1");
        com.retro.bamfos.updater.engine.ledger.Ledger led = new com.retro.bamfos.updater.engine.ledger.Ledger();
        com.retro.bamfos.updater.engine.ledger.Ledger.Entry entry =
                new com.retro.bamfos.updater.engine.ledger.Ledger.Entry("whatever-sha");
        entry.mergeName = "My BAMFOS";
        led.files.put("servers.dat", entry);
        led.save(com.retro.bamfos.updater.engine.ledger.Ledger.pathIn(dir));

        assertEquals(0, new UpdaterOps(dir, null).setServer("2.2.2.2", 25599));
        assertEquals("2.2.2.2:25599",
                ServersDat.entryIp(dir.resolve("servers.dat"), "My BAMFOS").orElse(""));
        assertTrue(ServersDat.entryIp(dir.resolve("servers.dat"), "BAMFOS").isEmpty());
    }

    /**
     * If the player renamed the pack entry and has their own server called "BAMFOS", falling back to the
     * default name would overwrite their server instead of moving the pack entry.
     */
    @Test
    void aRowThatOnlySharesTheDefaultNameIsNotTheRowWeOverlay() throws IOException {
        Path dir = Files.createTempDirectory("setserver");
        Files.createDirectories(props(dir).getParent());
        Files.writeString(props(dir), "server_host=1.1.1.1\n");
        // our entry, renamed by hand, plus a server of theirs that happens to be called BAMFOS too
        writeList(dir.resolve("servers.dat"), "My BAMFOS", "1.1.1.1", "BAMFOS", "play.jax.example:25566");
        com.retro.bamfos.updater.engine.ledger.Ledger led = new com.retro.bamfos.updater.engine.ledger.Ledger();
        com.retro.bamfos.updater.engine.ledger.Ledger.Entry entry =
                new com.retro.bamfos.updater.engine.ledger.Ledger.Entry("whatever-sha");
        entry.mergeName = "My BAMFOS";
        led.files.put("servers.dat", entry);
        led.save(com.retro.bamfos.updater.engine.ledger.Ledger.pathIn(dir));

        assertEquals(0, new UpdaterOps(dir, null).setServer("3.3.3.3", 25599));

        assertEquals("play.jax.example:25566",
                ServersDat.entryIp(dir.resolve("servers.dat"), "BAMFOS").orElse(""));
        assertEquals("3.3.3.3:25599",
                ServersDat.entryIp(dir.resolve("servers.dat"), "My BAMFOS").orElse(""));
    }

    /** Preserves user comments, formatting, and unrelated keys byte-for-byte. */
    @Test
    void theirCommentsAndOtherKeysSurviveTheRewrite() throws IOException {
        Path dir = Files.createTempDirectory("setserver");
        Files.createDirectories(props(dir).getParent());
        Files.writeString(props(dir), "# my notes, do not delete\nrepo=SixxHands/bamfos-pack\n"
                + "auto_restart=false\nserver_host = 1.1.1.1\n");

        assertEquals(0, new UpdaterOps(dir, null).setServer("4.4.4.4", 25565));

        String text = Files.readString(props(dir), StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("# my notes, do not delete"));
        assertTrue(text.contains("auto_restart=false"));
        assertEquals("SixxHands/bamfos-pack", UpdaterConfig.load(dir).repo());
        assertEquals("4.4.4.4", UpdaterConfig.load(dir).serverHost());
        assertEquals(1, text.lines().filter(l -> l.startsWith("server_host")).count());
    }

    /** Saving the same address that's already set shouldn't rewrite the file or report a change. */
    @Test
    void thesameAddressIsAnHonestNoOp() throws IOException {
        Path dir = Files.createTempDirectory("setserver");
        Files.createDirectories(props(dir).getParent());
        Files.writeString(props(dir), "server_host=5.5.5.5\nserver_port=25565\n");
        byte[] before = Files.readAllBytes(props(dir));

        assertEquals(1, new UpdaterOps(dir, null).setServer("5.5.5.5", 25565));
        assertEquals(-1, java.util.Arrays.mismatch(before, Files.readAllBytes(props(dir))));
    }

    @Test
    void junkIsRefusedBeforeAnythingIsWritten() throws IOException {
        Path dir = Files.createTempDirectory("setserver");
        Files.createDirectories(props(dir).getParent());
        Files.writeString(props(dir), "server_host=6.6.6.6\n");
        UpdaterOps ops = new UpdaterOps(dir, null);

        assertEquals(2, ops.setServer("", 25565));
        assertEquals(2, ops.setServer("has a space", 25565));
        assertEquals(2, ops.setServer("ok.example", 0));
        assertEquals(2, ops.setServer("ok.example", 70000));
        assertEquals("6.6.6.6", UpdaterConfig.load(dir).serverHost());
    }

    /**
     * If servers.dat is unreadable (e.g. locked by antivirus), properties still update and we return code 3
     * so the UI reports a partial update instead of claiming success.
     */
    @Test
    void anUnusableServerListIsReportedAsTheHalfItIs() throws IOException {
        Path dir = Files.createTempDirectory("setserver");
        Files.createDirectories(props(dir).getParent());
        Files.writeString(props(dir), "server_host=1.1.1.1\n");
        Files.createDirectories(dir.resolve("servers.dat")); // a directory at that name: readable, unusable

        assertEquals(3, new UpdaterOps(dir, null).setServer("7.7.7.7", 25565));
        assertEquals("7.7.7.7", UpdaterConfig.load(dir).serverHost());
    }

    /** Still updates cleanly when servers.dat doesn't exist yet (e.g. a fresh install). */
    @Test
    void noServerListYet_stillRePointsTheUpdater() throws IOException {
        Path dir = Files.createTempDirectory("setserver");
        Files.createDirectories(props(dir).getParent());
        Files.writeString(props(dir), "server_host=1.1.1.1\n");

        int code = new UpdaterOps(dir, null).setServer("8.8.8.8", 25577);
        assertEquals("8.8.8.8", UpdaterConfig.load(dir).serverHost());
        assertEquals(25577, UpdaterConfig.load(dir).serverPort());
        assertNotEquals(2, code);
        assertTrue(Files.isRegularFile(dir.resolve("servers.dat")));
        assertEquals("8.8.8.8:25577",
                ServersDat.entryIp(dir.resolve("servers.dat"), "BAMFOS").orElse(""));
    }

    /** Verify the written file can be parsed by the actual reader, not just our test helper. */
    @Test
    void whatWeWroteIsWhatTheRealParserReads() throws IOException {
        Path dir = Files.createTempDirectory("setserver");
        Files.createDirectories(props(dir).getParent());
        Files.writeString(props(dir), "# ünicode in a comment\nserver_host=1.1.1.1\n",
                StandardCharsets.ISO_8859_1);

        new UpdaterOps(dir, null).setServer("9.9.9.9", 25580);
        assertEquals("9.9.9.9", UpdaterConfig.load(dir).serverHost());
        assertEquals(25580, UpdaterConfig.load(dir).serverPort());
        Nbt.Root reread = Nbt.read(new ByteArrayInputStream(Files.readAllBytes(dir.resolve("servers.dat"))));
        assertTrue(reread.compound().containsKey("servers"));
    }
}
