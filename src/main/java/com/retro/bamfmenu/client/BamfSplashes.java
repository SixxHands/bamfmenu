package com.retro.bamfmenu.client;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/* Loads the mod-owned splash list from assets/bamfmenu/splashes.txt. Each line is either plain text
   (rendered in the vanilla yellow) or "#RRGGBB|text" to give that line a custom color. We keep the
   color out of the file vanilla reads, so the prefix never leaks into the displayed text. Text
   supports backslash escapes: \n becomes a line break (so a splash can span multiple lines), \" a
   quote, \\ a single backslash. The list is loaded once, lazily, from the classpath (timing-safe,
   no resource reload needed). */
public final class BamfSplashes {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CLASSPATH = "/assets/bamfmenu/splashes.txt";

    private static List<String> texts;
    private static Map<String, Integer> colors;

    private BamfSplashes() {}

    private static synchronized void ensureLoaded() {
        if (texts != null) {
            return;
        }
        List<String> t = new ArrayList<>();
        Map<String, Integer> c = new HashMap<>();
        try (InputStream in = BamfSplashes.class.getResourceAsStream(CLASSPATH)) {
            if (in != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("//")) {
                            continue;
                        }
                        Integer color = null;
                        String text = line;
                        /* Color prefix: #RRGGBB| or #RRGGBBAA| (the trailing alpha byte is accepted but
                           ignored -- the splash renderer applies the title-screen fade alpha itself, so
                           a hand-written AA would just fight it). The '|' sits at index 7 for 6 hex
                           digits, index 9 for 8. */
                        if (line.length() > 1 && line.charAt(0) == '#') {
                            int bar = -1;
                            if (line.length() >= 8 && line.charAt(7) == '|') {
                                bar = 7;
                            } else if (line.length() >= 10 && line.charAt(9) == '|') {
                                bar = 9;
                            }
                            if (bar > 0) {
                                try {
                                    color = Integer.parseInt(line.substring(1, 7), 16);
                                    text = line.substring(bar + 1).trim();
                                } catch (NumberFormatException ignored) {
                                    color = null;
                                    text = line;
                                }
                            }
                        }
                        text = unescape(text);
                        if (!text.isEmpty()) {
                            t.add(text);
                            if (color != null) {
                                c.put(text, color);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[bamfmenu] failed to load splashes", e);
        }
        texts = t;
        colors = c;
        LOGGER.info("[bamfmenu] loaded {} splash line(s), {} colored", texts.size(), colors.size());
    }

    /* A random splash line, or null if none are bundled (caller then falls back to vanilla). */
    public static String pickRandom() {
        ensureLoaded();
        if (texts.isEmpty()) {
            return null;
        }
        return texts.get(ThreadLocalRandom.current().nextInt(texts.size()));
    }

    /* The RGB color (no alpha) for a line, or null to use the vanilla yellow. */
    public static Integer colorFor(String splash) {
        ensureLoaded();
        return colors.get(splash);
    }

    /* Turns the file's two-character escapes into real characters: \n into a newline (so a splash can
       span multiple lines), \" into a quote, \\ into a single backslash. Unknown escapes keep their
       backslash so nothing is silently eaten. readLine() never hands us a real newline mid-entry, so
       \n is the only way to author a multi-line splash. */
    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == 'n') {
                    sb.append('\n');
                    i++;
                } else if (next == '"') {
                    sb.append('"');
                    i++;
                } else if (next == '\\') {
                    sb.append('\\');
                    i++;
                } else {
                    sb.append(ch);
                }
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}
