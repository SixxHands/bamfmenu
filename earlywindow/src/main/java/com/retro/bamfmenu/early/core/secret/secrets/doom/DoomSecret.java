package com.retro.bamfmenu.early.core.secret.secrets.doom;

import com.retro.bamfmenu.early.core.secret.api.Secret;
import com.retro.bamfmenu.early.core.secret.api.SecretDef;

/* Hidden game: typing `doom` in the Safe-For-Retro box launches the REAL vendored DOOM engine (Mocha Doom) rendered
   as colored ASCII in the terminal. The age-old question, answered: yes, it can run DOOM. */
public final class DoomSecret implements SecretDef {
    @Override
    public Secret secret() {
        return Secret.phrase("doom", "doom", ctx -> ctx.launchGame("doom"));
    }
}
