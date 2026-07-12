package com.retro.bamfmenu.early.core.secret.registry;

import com.retro.bamfmenu.early.core.util.ResourceScan;
import com.retro.bamfmenu.early.core.secret.api.Secret;
import com.retro.bamfmenu.early.core.secret.api.SecretContext;
import com.retro.bamfmenu.early.core.secret.api.SecretDef;
import com.retro.bamfmenu.early.core.secret.api.Trigger;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/* Discovers + matches all data-driven secrets. DISCOVERY auto-scans the per-secret folders: every secret is a
   FOLDER under core/secret/secrets/ with its declaring SecretDef class at the folder ROOT; the rest (game logic,
   renderers, probes) lives in a subfolder, out of discovery's sight. The scan (ResourceScan.listResourcesDeep, the
   proven union/jar/dir enumeration) matches `.class` files EXACTLY ONE level down, so the layout is self-enforcing:
   the machinery beside secrets/ and each secret's subfolder internals (two+ levels) are never touched; adding a secret
   is "make a folder under secrets/, drop the SecretDef at its root" with NO registration anywhere. MATCHING has two channels
   mirroring how a secret is entered: submit() on ENTER for Phrase secrets (case-insensitive), and feedKey() live for
   Keys secrets (a rolling key-history ring). One instance lives on the provider (it holds that ring). Pure logic,
   no GL, no Minecraft, so it builds at provider construction. */
public final class SecretRegistry {

    /* The container package whose per-secret FOLDERS hold the SecretDef classes (slash form, for the scan).
       CONTRACT: never put RESOURCE files under this package path (src/main/resources/com/...); in dev the scan
       walks whichever classpath dir getResource resolves first, and a resource shadow would list the wrong tree. */
    private static final String SECRETS_DIR = "com/retro/bamfmenu/early/core/secret/secrets";
    /* Exactly one folder level down: <secretname>/<Class>.class, nothing at the root, nothing deeper. FOLDER
       NAMES MUST BE LOWERCASE a-z0-9 (no '_', no caps): anything else is silently invisible to this pattern. */
    private static final String DEF_PATTERN = "[a-z0-9]+/\\w+\\.class";
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

    /* Auto-discover every SecretDef at a per-secret folder root by listing `.class` files exactly one level under
       secret/secrets/ (the SAME union/jar/dir enumeration song discovery uses) and reflectively instantiating each via its
       no-arg constructor. A class that isn't a concrete SecretDef (e.g. a helper someone parks beside the def), or
       whose secret() is null/broken, is skipped; a bad def never breaks the rest. */
    private static List<Secret> discover() {
        List<Secret> out = new ArrayList<>();
        ClassLoader cl = SecretRegistry.class.getClassLoader();
        for (String path : ResourceScan.listResourcesDeep(SECRETS_DIR, DEF_PATTERN)) {
            // path is "/com/retro/.../secret/<name>/Foo.class" -> the fully-qualified class name
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
