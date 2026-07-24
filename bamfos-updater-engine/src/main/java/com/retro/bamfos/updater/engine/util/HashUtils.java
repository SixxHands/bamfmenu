package com.retro.bamfos.updater.engine.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** sha256 as lowercase hex. The manifest's per-file sha256 is the integrity authority. */
public final class HashUtils {

    public static String sha256(byte[] data) {
        return hex(digest().digest(data));
    }

    public static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return sha256(in);
        }
    }

    public static String sha256(InputStream in) throws IOException {
        MessageDigest d = digest();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) >= 0) {
            d.update(buf, 0, n);
        }
        return hex(d.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-256 does not exist in the wild", e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    private HashUtils() {
    }
}
