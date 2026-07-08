package com.retro.bamfmenu.early.core.secret.defs;

import com.retro.bamfmenu.early.core.secret.Secret;
import com.retro.bamfmenu.early.core.secret.SecretDef;

/* Hidden game: typing `galaga` in the Safe-For-Retro box launches the Galaga mini-game (a full fixed-shooter with
   the formation dives + the capture-beam / dual-fighter). Mirrors SnakeSecret -- a secret that reveals a hidden
   GAME via launchGame; auto-discovered by SecretRegistry (drop a SecretDef in this package, no registration). */
public final class GalagaSecret implements SecretDef {
    @Override
    public Secret secret() {
        return Secret.phrase("galaga", "galaga", ctx -> ctx.launchGame("galaga"));
    }
}
