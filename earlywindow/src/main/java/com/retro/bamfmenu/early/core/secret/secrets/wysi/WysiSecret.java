package com.retro.bamfmenu.early.core.secret.secrets.wysi;

import com.retro.bamfmenu.early.core.secret.api.Secret;
import com.retro.bamfmenu.early.core.secret.api.SecretDef;

/* wysi 
   Assets live at /wysi/f00.jpg.. + /wysi/audio.ogg (regenerate from a new mp4 with the ffmpeg pass in scratch/wysi-src/README).
   If the frames are ever missing the secret falls back to the still-image ramp-fade of /wysi.png. The template for
   a VIDEO-CUTSCENE secret. */
public final class WysiSecret implements SecretDef {
    @Override
    public Secret secret() {
        return Secret.phrase("wysi", "wysi", ctx -> ctx.videoCutscene("/wysi"));
    }
}
