package com.retro.bamfmenu.early.core.secret.defs;

import com.retro.bamfmenu.early.core.secret.Secret;
import com.retro.bamfmenu.early.core.secret.SecretDef;

/* Hidden game: typing `snake` in the Safe-For-Retro box launches the Snake mini-game (a glitch-shimmer snake, the
   recovery cutscene's effect). The template for a secret that reveals a hidden GAME via launchGame. */
public final class SnakeSecret implements SecretDef {
    @Override
    public Secret secret() {
        return Secret.phrase("snake", "snake", ctx -> ctx.launchGame("snake"));
    }
}
