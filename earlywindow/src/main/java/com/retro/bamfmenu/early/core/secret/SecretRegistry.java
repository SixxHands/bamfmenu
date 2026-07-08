package com.retro.bamfmenu.early.core.secret;

import com.retro.bamfmenu.early.core.song.SongLoader;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/* Discovers + matches all data-driven secrets. DISCOVERY auto-scans the secret/defs package: it reuses
   SongLoader.listResources (the proven union/jar/dir enumeration) to list the defs' `.class` files, then reflectively
   instantiates each SecretDef and collects its Secret -- so adding a secret is "drop a SecretDef file in defs/" with
   NO registration anywhere. MATCHING has two channels mirroring how a secret is entered: submit() on ENTER for Phrase
   secrets (case-insensitive), and feedKey() live for Keys secrets (a rolling key-history ring). One instance lives on
   the provider (it holds that ring). Pure logic -- no GL, no Minecraft -- so it builds at provider construction. */
public final class SecretRegistry {

    /* The package whose .class files are the per-secret SecretDef classes (slash form, for the resource scan). */
    private static final String DEFS_DIR = "com/retro/bamfmenu/early/core/secret/defs";
    private static final int HISTORY = 24; // rolling key buffer; must exceed the longest key sequence

    private final List<Secret> secrets;
    private final int[] keyHistory = new int[HISTORY];
    private int keyCount;

    public SecretRegistry() {
        this.secrets = discover();
        System.err.println("[bamfmenu] secrets: " + secrets.size() + " loaded");
    }

    public int count() {
        return secrets.size();
    }

    /* ENTER in the secret box: run the FIRST Phrase secret whose phrase matches `typed` (case-insensitive). Returns
       whether one fired (the caller shows "access denied" when nothing matched). */
    public boolean submit(String typed, SecretContext ctx) {
        for (Secret s : secrets) {
            if (s.trigger() instanceof Trigger.Phrase p && p.matches(typed)) {
                fire(s, ctx);
                return true;
            }
        }
        return false;
    }

    /* A key PRESS in the secret box: push it to the rolling history, then fire any Keys secret whose run just
       completed. Returns whether one fired (the caller then closes the box). Feed only on press, not repeat. */
    public boolean feedKey(int code, SecretContext ctx) {
        if (keyCount < HISTORY) {
            keyHistory[keyCount++] = code;
        } else {
            System.arraycopy(keyHistory, 1, keyHistory, 0, HISTORY - 1);
            keyHistory[HISTORY - 1] = code;
        }
        for (Secret s : secrets) {
            if (s.trigger() instanceof Trigger.Keys k && k.endsWith(keyHistory, keyCount)) {
                keyCount = 0; // consume the run so it can't immediately re-fire
                fire(s, ctx);
                return true;
            }
        }
        return false;
    }

    /* Forget any in-progress key sequence (called when the secret box opens/closes so a stale run can't carry over). */
    public void resetKeys() {
        keyCount = 0;
    }

    private void fire(Secret s, SecretContext ctx) {
        try {
            s.action().run(ctx);
        } catch (Throwable t) {
            System.err.println("[bamfmenu] secret '" + s.name() + "' failed: " + t);
        }
    }

    /* Auto-discover every SecretDef under secret/defs/ by listing the package's `.class` files (the SAME union/jar/dir
       enumeration song discovery uses) and reflectively instantiating each via its no-arg constructor. A class that
       isn't a concrete SecretDef, or whose secret() is null/broken, is skipped -- a bad def never breaks the rest. */
    private static List<Secret> discover() {
        List<Secret> out = new ArrayList<>();
        ClassLoader cl = SecretRegistry.class.getClassLoader();
        for (String path : SongLoader.listResources(DEFS_DIR, "\\w+\\.class")) {
            // path is "/com/retro/.../defs/Foo.class" -> the fully-qualified class name
            String fqn = path.substring(1, path.length() - ".class".length()).replace('/', '.');
            try {
                Class<?> cls = Class.forName(fqn, false, cl);
                if (!SecretDef.class.isAssignableFrom(cls) || cls.isInterface()
                        || Modifier.isAbstract(cls.getModifiers())) {
                    continue;
                }
                SecretDef def = (SecretDef) cls.getDeclaredConstructor().newInstance();
                Secret s = def.secret();
                if (s != null && s.trigger() != null && s.action() != null) {
                    out.add(s);
                }
            } catch (Throwable t) {
                System.err.println("[bamfmenu] secret def skipped (" + fqn + "): " + t);
            }
        }
        return out;
    }
}
