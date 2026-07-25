package com.retro.bamfos.updater.engine.ping;

import com.retro.bamfos.updater.engine.Fx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** servers.dat merge: our entry inserted/refreshed, EVERYTHING the player owns preserved. */
class ServersMergeTest {

    @TempDir
    Path dir;

    private static final byte[] SEED = Fx.serversDat("198.51.100.10");

    @Test
    void nbtTreeRoundTripsByteExact() throws IOException {
        byte[] original = Fx.serversDat("1.1.1.1", "2.2.2.2:25566");
        Nbt.Root tree = Nbt.read(new ByteArrayInputStream(original));
        assertArrayEquals(original, Nbt.write(tree));
    }

    @Test
    void staleIp_isRefreshed_friendsEntriesUntouched() throws IOException {
        // BAMFOS on an OLD ip + a player's server after it
        Path local = dir.resolve("servers.dat");
        Files.write(local, Fx.serversDat("9.9.9.9", "friends.server.net"));
        String name = ServersDat.mergeFirstEntry(local, SEED);
        assertEquals("BAMFOS", name);
        assertEquals("198.51.100.10", ServersDat.entryIp(local, "BAMFOS").orElseThrow());
        assertEquals("198.51.100.10", ServersDat.firstIp(local).orElseThrow());
        // the player's entry survived with its ip intact
        Nbt.Root tree = Nbt.read(Files.newInputStream(local));
        assertTrue(new String(Nbt.write(tree)).contains("friends.server.net"));
    }

    @Test
    void missingEntry_isInsertedFirst_othersKeepOrder() throws IOException {
        Path local = dir.resolve("servers.dat");
        Files.write(local, Fx.serversDat("friends.server.net", "other.net"));
        // the fixture names every entry BAMFOS, so hand-build a local file with different names
        Nbt.Root tree = Nbt.read(new ByteArrayInputStream(Files.readAllBytes(local)));
        var servers = (Nbt.ListVal) tree.compound().get("servers").v();
        int i = 0;
        for (Nbt.Val item : servers.items()) {
            @SuppressWarnings("unchecked")
            var entry = (java.util.LinkedHashMap<String, Nbt.Val>) item.v();
            entry.put("name", new Nbt.Val(0x08, "Friend " + (++i)));
        }
        Files.write(local, Nbt.write(tree));

        ServersDat.mergeFirstEntry(local, SEED);
        assertEquals("198.51.100.10", ServersDat.firstIp(local).orElseThrow());
        assertEquals("friends.server.net", ServersDat.entryIp(local, "Friend 1").orElseThrow());
        assertEquals("other.net", ServersDat.entryIp(local, "Friend 2").orElseThrow());
    }

    @Test
    void currentIp_noWrite_mtimePreserved() throws IOException {
        Path local = dir.resolve("servers.dat");
        Files.write(local, Fx.serversDat("198.51.100.10"));
        java.nio.file.attribute.FileTime before = Files.getLastModifiedTime(local);
        ServersDat.mergeFirstEntry(local, SEED);
        assertEquals(before, Files.getLastModifiedTime(local));
    }

    @Test
    void garbageLocal_becomesTheSeed() throws IOException {
        Path local = dir.resolve("servers.dat");
        Files.write(local, new byte[]{1, 2, 3, 4});
        ServersDat.mergeFirstEntry(local, SEED);
        assertArrayEquals(SEED, Files.readAllBytes(local));
    }

    @Test
    void absentLocal_becomesTheSeed() throws IOException {
        Path local = dir.resolve("servers.dat");
        ServersDat.mergeFirstEntry(local, SEED);
        assertArrayEquals(SEED, Files.readAllBytes(local));
    }
}
