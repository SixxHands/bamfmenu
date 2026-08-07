package com.retro.bamfmenu.client;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/* Makes sure the early window actually boots into OUR BIOS terminal. FML reads config/fml.toml at boot
   (BEFORE any mod loads) to pick the ImmediateWindowProvider by name, so this can only correct the file
   for the NEXT launch, it cannot redirect the current boot. Run once on the client: if
   earlyWindowProvider isn't "bamfmenu_terminal" or earlyWindowControl isn't true, back the file up
   (fml.toml.bak) and rewrite ONLY those two keys, leaving everything else untouched. Idempotent, a
   file that is already correct is not rewritten. Best-effort: any failure is logged and swallowed so it
   never blocks startup.

   This lives in the PARENT @Mod (which depends on the BIOS service jar), not in the service jar: the
   service jar runs at the FML service layer before this could act, and "ensure my early window is
   selected next time" is naturally the parent mod's job. */
public final class EarlyWindowKeying {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String PROVIDER_KEY = "earlyWindowProvider";
    private static final String PROVIDER_VALUE = "\"bamfmenu_terminal\""; // TOML string literal (quoted)
    private static final String CONTROL_KEY = "earlyWindowControl";
    private static final String CONTROL_VALUE = "true";

    private EarlyWindowKeying() {}

    public static void ensureProvider() {
        try {
            Path toml = FMLPaths.CONFIGDIR.get().resolve("fml.toml");
            boolean existed = Files.exists(toml);
            List<String> lines = existed
                    ? new ArrayList<>(Files.readAllLines(toml, StandardCharsets.UTF_8))
                    : new ArrayList<>();

            boolean changed = false;
            changed |= ensureKey(lines, PROVIDER_KEY, PROVIDER_VALUE);
            changed |= ensureKey(lines, CONTROL_KEY, CONTROL_VALUE);

            if (!changed) {
                LOGGER.debug("[bamfmenu] fml.toml early-window keying already correct");
                return;
            }
            if (existed) {
                Files.copy(toml, toml.resolveSibling("fml.toml.bak"), StandardCopyOption.REPLACE_EXISTING);
            } else if (toml.getParent() != null) {
                Files.createDirectories(toml.getParent());
            }
            Files.write(toml, lines, StandardCharsets.UTF_8);
            LOGGER.info("[bamfmenu] corrected early-window keying in {} (takes effect next launch)", toml);
        } catch (Throwable t) {
            LOGGER.warn("[bamfmenu] could not ensure fml.toml early-window keying", t);
        }
    }

    /* Ensure the assignment "<key> = <desiredValue>" exists: replace the current assignment if its value
       differs, else leave it; append the line if the key is absent. Skips comment lines (#...) and only
       matches the EXACT key token before '=' (so earlyWindowControl is not confused with earlyWindow*
       siblings). Returns true if the line list was modified. */
    private static boolean ensureKey(List<String> lines, String key, String desiredValue) {
        String desiredLine = key + " = " + desiredValue;
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("#") || !trimmed.startsWith(key)) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq < 0 || !trimmed.substring(0, eq).trim().equals(key)) {
                continue;
            }
            if (trimmed.substring(eq + 1).trim().equals(desiredValue)) {
                return false; // already correct -> no change
            }
            lines.set(i, desiredLine);
            return true;
        }
        lines.add(desiredLine);
        return true;
    }
}
