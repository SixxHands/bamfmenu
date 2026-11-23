package com.retro.bamfmenu.early.core.rhythm.registry;

import com.retro.bamfmenu.early.core.rhythm.model.SongDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OsuDropInTest {

    private static final String SAMPLE_OSU = ""
            + "osu file format v14\n\n"
            + "[General]\n"
            + "Mode: 3\n\n"
            + "[Metadata]\n"
            + "Title:Test Drop Song\n"
            + "Artist:Test Artist\n"
            + "Version:Normal\n\n"
            + "[Difficulty]\n"
            + "CircleSize:4\n\n"
            + "[TimingPoints]\n"
            + "0,500,4,1,0,100,1,0\n\n"
            + "[HitObjects]\n"
            + "64,192,1000,1,0,0:0:0:0:\n"
            + "192,192,1500,1,0,0:0:0:0:\n"
            + "320,192,2000,1,0,0:0:0:0:\n"
            + "448,192,2500,1,0,0:0:0:0:\n";

    @TempDir
    Path dir;

    @Test
    void discoverDiskImportsDroppedOszAndMarksItImported() throws IOException {
        Path songsDir = dir.resolve("songs");
        Files.createDirectories(songsDir);
        Path osz = songsDir.resolve("12345 Test Drop Song.osz");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(osz))) {
            zos.putNextEntry(new ZipEntry("chart.osu"));
            zos.write(SAMPLE_OSU.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("audio.ogg"));
            zos.write(new byte[]{'O', 'g', 'g', 'S', 0, 2});
            zos.closeEntry();
        }

        List<SongDef> discovered = SongLoader.discoverDisk(songsDir);

        assertEquals(1, discovered.size());
        SongDef song = discovered.get(0);
        assertEquals("Test Drop Song", song.id());
        assertEquals("Test Drop Song", song.name());
        assertNotNull(song.ogg());
        assertTrue(song.ogg().startsWith("file:"));
        assertFalse(Files.exists(osz));
        assertTrue(Files.exists(songsDir.resolve("12345 Test Drop Song.osz.imported")));
        assertTrue(Files.isRegularFile(songsDir.resolve("Test Drop Song/normal.chart")));

        List<SongDef> secondPass = SongLoader.discoverDisk(songsDir);
        assertEquals(1, secondPass.size());
    }

    @Test
    void discoverDiskImportsExtractedOsuFolderInPlace() throws IOException {
        Path songsDir = dir.resolve("songs");
        Path folder = songsDir.resolve("extracted-map");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("map.osu"), SAMPLE_OSU, StandardCharsets.UTF_8);
        Files.write(folder.resolve("audio.ogg"), new byte[]{'O', 'g', 'g', 'S', 0, 2});

        List<SongDef> discovered = SongLoader.discoverDisk(songsDir);

        assertEquals(1, discovered.size());
        assertEquals("extracted-map", discovered.get(0).id());
        assertTrue(Files.isRegularFile(folder.resolve("song.json")));
        assertTrue(Files.isRegularFile(folder.resolve("normal.chart")));
    }

    @Test
    void discoverDiskImportsOszInsideSongFolderInPlace() throws IOException {
        Path songsDir = dir.resolve("songs");
        Path folder = songsDir.resolve("my-osu-song");
        Files.createDirectories(folder);
        Path osz = folder.resolve("99999 Map.osz");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(osz))) {
            zos.putNextEntry(new ZipEntry("chart.osu"));
            zos.write(SAMPLE_OSU.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("audio.ogg"));
            zos.write(new byte[]{'O', 'g', 'g', 'S', 0, 2});
            zos.closeEntry();
        }

        List<SongDef> discovered = SongLoader.discoverDisk(songsDir);

        assertEquals(1, discovered.size());
        assertEquals("my-osu-song", discovered.get(0).id());
        assertFalse(Files.exists(osz));
        assertTrue(Files.exists(folder.resolve("99999 Map.osz.imported")));
        assertTrue(Files.isRegularFile(folder.resolve("song.json")));
        assertTrue(Files.isRegularFile(folder.resolve("normal.chart")));
    }

    @Test
    void bundledSongsExcludeTetrisBeatSong() {
        SongDef[] bundled = SongLoader.discover();
        boolean hasTetris = Arrays.stream(bundled).anyMatch(s -> "tetris".equals(s.id()));
        assertFalse(hasTetris);
    }
}
