package com.retro.bamfos.updater.engine.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class ServerIpEnv {

    private static final String RESOURCE = "/server-ip.env";

    private ServerIpEnv() {
    }

    public static String raw() {
        try (InputStream in = ServerIpEnv.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return "";
            }
            return parseValue(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    public static String host() {
        return extractHost(raw());
    }

    public static int port(int fallback) {
        return extractPort(raw(), fallback);
    }

    public static String parseValue(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String cleaned = text.charAt(0) == 0xFEFF ? text.substring(1) : text;
        for (String rawLine : cleaned.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq > 0) {
                String key = line.substring(0, eq).trim();
                if (!key.equals("SERVER_IP")) {
                    continue;
                }
                return unquote(line.substring(eq + 1).trim());
            }
            return unquote(line);
        }
        return "";
    }

    public static String extractHost(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.trim();
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            return close > 1 ? s.substring(1, close).trim() : s;
        }
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.indexOf(':') == colon) {
            return s.substring(0, colon).trim();
        }
        return s;
    }

    public static int extractPort(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String s = raw.trim();
        String portStr = null;
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close > 0 && close + 1 < s.length() && s.charAt(close + 1) == ':') {
                portStr = s.substring(close + 2).trim();
            }
        } else {
            int colon = s.lastIndexOf(':');
            if (colon > 0 && s.indexOf(':') == colon) {
                portStr = s.substring(colon + 1).trim();
            }
        }
        if (portStr == null || portStr.isEmpty()) {
            return fallback;
        }
        try {
            int p = Integer.parseInt(portStr);
            return p >= 1 && p <= 65535 ? p : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1).trim();
        }
        return s;
    }
}
