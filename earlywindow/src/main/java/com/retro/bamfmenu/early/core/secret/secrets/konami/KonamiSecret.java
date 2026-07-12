package com.retro.bamfmenu.early.core.secret.secrets.konami;

import com.retro.bamfmenu.early.core.secret.api.Secret;
import com.retro.bamfmenu.early.core.secret.api.SecretDef;
import com.retro.bamfmenu.early.core.secret.api.Trigger;

/* Example KEY-SEQUENCE secret: the Konami code (UP UP DOWN DOWN LEFT RIGHT LEFT RIGHT B A), pressed with the real
   keys in the secret box, fires a flavor notice. The TEMPLATE for a sequence secret: swap the codes and the action
   (e.g. `ctx -> ctx.launchSong("<hidden-id>")` to reveal a hidden song). GLFW key codes are inlined so core stays
   free of the LWJGL import. Auto-discovered (drop-in); no registration needed. */
public final class KonamiSecret implements SecretDef {

    // GLFW key codes (kept inline to avoid an LWJGL dependency in core): arrows + B + A.
    private static final int UP = 265, DOWN = 264, LEFT = 263, RIGHT = 262, B = 66, A = 65;

    @Override
    public Secret secret() {
        return new Secret("konami",
                Trigger.keys(UP, UP, DOWN, DOWN, LEFT, RIGHT, LEFT, RIGHT, B, A),
                ctx -> ctx.notice("^ ^ v v < > < > B A  --  you know the code."));
    }
}
