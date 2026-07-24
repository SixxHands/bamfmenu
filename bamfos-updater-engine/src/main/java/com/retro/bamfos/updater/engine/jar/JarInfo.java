package com.retro.bamfos.updater.engine.jar;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// Reads modIds from neoforge.mods.toml (and META-INF/services for service jars) for duplicate-mod detection.
public final class JarInfo {

    private static final Pattern MOD_ID = Pattern.compile("^\\s*modId\\s*=\\s*\"([^\"]+)\"");

    public record Identity(List<String> modids, List<String> services) {
        public boolean isServiceJar() {
            return modids.isEmpty() && !services.isEmpty();
        }
    }

    public static Identity read(java.nio.file.Path jar) throws IOException {
        List<String> modids = new ArrayList<>();
        List<String> services = new ArrayList<>();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry toml = z.getEntry("META-INF/neoforge.mods.toml");
            if (toml != null) {
                boolean inMods = false;
                try (BufferedReader r = new BufferedReader(new InputStreamReader(z.getInputStream(toml), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        String t = line.trim();
                        if (t.startsWith("[[mods]]")) {
                            inMods = true;
                        } else if (t.startsWith("[[") || (t.startsWith("[") && !t.startsWith("[["))) {
                            inMods = false; // any other table ends the mods block
                        } else if (inMods) {
                            Matcher m = MOD_ID.matcher(line);
                            if (m.find()) {
                                modids.add(m.group(1));
                            }
                        }
                    }
                }
            }
            var entries = z.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                if (n.startsWith("META-INF/services/") && !e.isDirectory() && n.length() > "META-INF/services/".length()) {
                    services.add(n.substring("META-INF/services/".length()));
                }
            }
        }
        return new Identity(List.copyOf(modids), List.copyOf(services));
    }

    private JarInfo() {
    }
}
