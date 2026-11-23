package com.retro.bamfos.updater.engine.ping;

import com.retro.bamfos.updater.engine.Fx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ServersDatTest {

    @TempDir
    Path dir;

    @Test
    void firstEntryIpWins() throws IOException {
        Path f = dir.resolve("servers.dat");
        Files.write(f, Fx.serversDat(Fx.serverIp(), "other.example.com"));
        assertEquals(Fx.serverIp(), ServersDat.firstIp(f).orElseThrow());
    }

    @Test
    void hostPortFormStaysRaw() throws IOException {
        // Caller handles host/port splitting (including IPv6); this reader just returns the raw string.
        Path f = dir.resolve("servers.dat");
        Files.write(f, Fx.serversDat("127.0.0.1:25566"));
        assertEquals("127.0.0.1:25566", ServersDat.firstIp(f).orElseThrow());
    }

    @Test
    void ipAfterNonStringTags_skipWalksPastThem() throws IOException {
        // Vanilla writes hidden (byte) and icon (byte array) around ip; parser should skip them cleanly.
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(0x0A);
        out.writeUTF("");
        out.writeByte(0x09);
        out.writeUTF("servers");
        out.writeByte(0x0A);
        out.writeInt(1);
        out.writeByte(0x01);
        out.writeUTF("hidden");
        out.writeByte(0);
        out.writeByte(0x07);
        out.writeUTF("icon");
        out.writeInt(3);
        out.write(new byte[]{1, 2, 3});
        out.writeByte(0x08);
        out.writeUTF("ip");
        out.writeUTF(Fx.serverIp());
        out.writeByte(0x00);
        out.writeByte(0x00);
        Path f = dir.resolve("servers.dat");
        Files.write(f, bos.toByteArray());
        assertEquals(Fx.serverIp(), ServersDat.firstIp(f).orElseThrow());
    }

    @Test
    void noSignalShapes_allEmptyNeverThrow() throws IOException {
        assertTrue(ServersDat.firstIp(dir.resolve("absent.dat")).isEmpty());
        Path garbage = dir.resolve("garbage.dat");
        Files.write(garbage, new byte[]{0x1F, (byte) 0x8B, 42, 42}); // Gzip header: compressed servers.dat should be ignored too.
        assertTrue(ServersDat.firstIp(garbage).isEmpty());
        Path empty = dir.resolve("empty.dat");
        Files.write(empty, Fx.serversDat());
        assertTrue(ServersDat.firstIp(empty).isEmpty());
    }
}
