package com.retro.bamfmenu.early.core.secret;

/* One secret, declared in its OWN class under secret/defs/. Implement this, return your `new Secret(...)`, and the
   SecretRegistry auto-discovers it (a jar/dir scan of the defs package) -- there is NO central list to edit, you just
   drop a file. Needs a public no-arg constructor (the implicit default is fine). Keep one secret per class. */
public interface SecretDef {
    Secret secret();
}
