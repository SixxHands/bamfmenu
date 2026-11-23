package com.retro.bamfmenu.early.core.tetris;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TetrisPlaylistTest {

    @TempDir
    Path dir;

    @Test
    void discoverDiskScaffoldsReadmeAndFindsOggFiles() throws IOException {
        Path tetrisDir = dir.resolve("tetris");
        Files.createDirectories(tetrisDir);
        Path trackB = tetrisDir.resolve("b-custom.ogg");
        Path trackA = tetrisDir.resolve("a-custom.ogg");
        Files.write(trackB, new byte[]{1, 2, 3});
        Files.write(trackA, new byte[]{4, 5, 6});
        Files.writeString(tetrisDir.resolve("ignored.mp3"), "nope", StandardCharsets.UTF_8);

        List<String> found = TetrisPlaylist.discoverDisk(tetrisDir);

        assertEquals(List.of("file:" + trackA.toAbsolutePath(), "file:" + trackB.toAbsolutePath()), found);
        assertTrue(Files.isRegularFile(tetrisDir.resolve("readme.md")));
    }

    @Test
    void ensureReadmeDoesNotOverwriteExistingFile() throws IOException {
        Path tetrisDir = dir.resolve("tetris");
        Files.createDirectories(tetrisDir);
        Path readme = tetrisDir.resolve("readme.md");
        Files.writeString(readme, "custom notes", StandardCharsets.UTF_8);

        TetrisPlaylist.ensureReadme(tetrisDir);

        assertEquals("custom notes", Files.readString(readme, StandardCharsets.UTF_8));
    }
}
