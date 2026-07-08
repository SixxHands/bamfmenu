package com.retro.bamfmenu.early.core.secret.defs;

import com.retro.bamfmenu.early.core.secret.Secret;
import com.retro.bamfmenu.early.core.secret.SecretDef;

/* The classic "password" Easter egg: typing `password` (any case) in the Safe-For-Retro secret box toggles the dev
   no-fail / no-score mode. This is the ORIGINAL hardcoded check, now expressed as one data-driven secret -- the
   template for a PHRASE secret. Auto-discovered (drop-in); no registration needed. */
public final class SafeForRetroSecret implements SecretDef {
    @Override
    public Secret secret() {
        return Secret.phrase("safe-for-retro", "password", ctx -> ctx.toggleSafeForRetro());
    }
}
