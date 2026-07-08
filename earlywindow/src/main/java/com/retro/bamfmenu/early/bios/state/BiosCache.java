package com.retro.bamfmenu.early.bios.state;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/* Persistence for the BIOS's saved state (unlocks, the Recovery/achievement flags, the Safe-For-Retro
   switch, per-song high scores + grades, volume, theme, lane keybinds). It lives OUT of the config
   folder, in the mod's own data directory <gameDir>/BAMFOS/cache/bios.dat, as an OPAQUE BINARY blob --
   not a plaintext .properties a player edits in Notepad.

   Deterrent only, by design: the de-scramble logic ships in this jar, so a determined user with the jar
   can still decode it. The goal is "out of normal eyesight + not casually tamperable", not real
   security; there is no checksum/signature.

   The in-memory shape stays java.util.Properties (so the provider's load/save logic is unchanged); only
   the on-disk encoding + location differ. First run migrates the old plaintext
   <gameDir>/config/bamfos_bios.properties into the new cache, then deletes it, so existing saves carry
   over. No GL/Minecraft refs -- safe in the service jar. */
public final class BiosCache {

    private static final String DATA_DIR = "BAMFOS";
    private static final String CACHE_SUBDIR = "cache";
    private static final String CACHE_FILE = "bios.dat";

    /* A 3-byte magic so a stray/foreign file is rejected (load returns empty) rather than mis-decoded. */
    private static final byte[] MAGIC = {(byte) 0xB1, 0x05, 0x01};
    /* Fixed scramble key -- deterrent only (it ships in the jar). */
    private static final byte[] KEY = "b4mf0s.k3y.v1".getBytes(StandardCharsets.US_ASCII);

    private BiosCache() {}

    public static Path cacheDir(String gameDir) {
        return Paths.get(gameDir, DATA_DIR, CACHE_SUBDIR);
    }

    public static Path cacheFile(String gameDir) {
        return cacheDir(gameDir).resolve(CACHE_FILE);
    }

    private static Path legacyFile(String gameDir) {
        return Paths.get(gameDir, "config", "bamfos_bios.properties");
    }

    /* Read the saved state. Returns an empty Properties (all defaults) if nothing is stored yet or the
       file is unreadable/foreign. Migrates the old plaintext config on first run. Best-effort. */
    public static Properties load(String gameDir) {
        Properties props = new Properties();
        try {
            Path f = cacheFile(gameDir);
            if (Files.exists(f)) {
                byte[] plain = decode(Files.readAllBytes(f));
                if (plain != null) {
                    props.load(new ByteArrayInputStream(plain));
                }
                return props;
            }
            /* Migration: import the pre-refactor plaintext file once, write the cache, delete the old. */
            Path old = legacyFile(gameDir);
            if (Files.exists(old)) {
                try (InputStream in = Files.newInputStream(old)) {
                    props.load(in);
                }
                save(gameDir, props);
                try {
                    Files.deleteIfExists(old);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return props;
    }

    /* Write the saved state as the obfuscated binary blob (creating BAMFOS/cache on first save). */
    public static void save(String gameDir, Properties props) {
        try {
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            props.store(text, "BAMFOS"); // serialized to a byte buffer, then scrambled below
            byte[] blob = encode(text.toByteArray());
            Path dir = cacheDir(gameDir);
            Files.createDirectories(dir);
            Files.write(dir.resolve(CACHE_FILE), blob);
        } catch (Throwable ignored) {
        }
    }

    /* Reset Cache: drop the stored state entirely. */
    public static void delete(String gameDir) {
        try {
            Files.deleteIfExists(cacheFile(gameDir));
        } catch (Throwable ignored) {
        }
    }

    /* magic header + position-varying XOR -> opaque bytes (no integrity check, deterrent only). */
    private static byte[] encode(byte[] plain) {
        byte[] out = new byte[MAGIC.length + plain.length];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        for (int i = 0; i < plain.length; i++) {
            out[MAGIC.length + i] = (byte) (plain[i] ^ KEY[i % KEY.length] ^ (i * 31));
        }
        return out;
    }

    private static byte[] decode(byte[] raw) {
        if (raw.length < MAGIC.length) {
            return null;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (raw[i] != MAGIC[i]) {
                return null; // not our file
            }
        }
        byte[] plain = new byte[raw.length - MAGIC.length];
        for (int i = 0; i < plain.length; i++) {
            plain[i] = (byte) (raw[MAGIC.length + i] ^ KEY[i % KEY.length] ^ (i * 31));
        }
        return plain;
    }
}
