package com.retro.bamfmenu.early.core.tetris;

import com.retro.bamfmenu.early.core.util.ResourceScan;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class TetrisPlaylist {

    private TetrisPlaylist() {
    }

    public static List<String> discover(Path tetrisDir) {
        List<String> out = new ArrayList<>(ResourceScan.listResources("songs", "tetris\\d+\\.ogg"));
        out.addAll(discoverDisk(tetrisDir));
        return List.copyOf(out);
    }

    public static List<String> discoverDisk(Path tetrisDir) {
        List<String> out = new ArrayList<>();
        if (tetrisDir == null) {
            return out;
        }
        ensureReadme(tetrisDir);
        if (!Files.isDirectory(tetrisDir)) {
            return out;
        }
        List<Path> oggs = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(tetrisDir, "*.ogg")) {
            for (Path p : stream) {
                if (Files.isRegularFile(p) && Files.isReadable(p)) {
                    oggs.add(p);
                }
            }
        } catch (Throwable t) {
            System.err.println("[bamfmenu] tetris disk music scan error: " + t);
            return out;
        }
        oggs.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()));
        for (Path p : oggs) {
            out.add("file:" + p.toAbsolutePath());
        }
        return List.copyOf(out);
    }

    public static void ensureReadme(Path tetrisDir) {
        if (tetrisDir == null) {
            return;
        }
        try {
            Files.createDirectories(tetrisDir);
            Path readme = tetrisDir.resolve("readme.md");
            if (Files.exists(readme)) {
                return;
            }
            String text = "# BAMFOS Tetris Music (BYOM)\n\n"
                    + "Drop any `.ogg` files into this folder to add them to the Tetris jukebox.\n"
                    + "They shuffle alongside the bundled tracks automatically on the next game.\n";
            Files.writeString(readme, text, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            System.err.println("[bamfmenu] tetris readme write failed: " + t);
        }
    }
}
