package com.retro.bamfmenu.early.core.secret.api;

/* The host seam a secret's action acts through (the BiosContext pattern). Kept ADDITIVE: a new secret power
   is ONE method here + an implementation on the provider. core/secret defines it, bios/ implements it, so
   core never depends on the presentation host. */
public interface SecretContext {

    /* The dev no-fail / no-score mode (the classic password easter egg). */
    void toggleSafeForRetro();

    /* One-line message on the BIOS SETUP screen. */
    void notice(String message);

    /* Launch by id in the rhythm game (e.g. a HIDDEN song); an unknown id shows a notice, never throws. */
    void launchSong(String songId);

    /* Launch a hidden mini-game by id (e.g. "snake"); an unknown id shows a notice, never throws. */
    void launchGame(String gameId);

    /* Fade a bundled image ("/wysi.png") into the SETUP help cell while all input locks; a missing resource shows a notice instead. */
    void imageCutscene(String resourcePath);

    /* Like imageCutscene but a clip with sound: <base>/f00.jpg.. + <base>/audio.ogg (the dev-time ffmpeg pass); missing frames fall back to the <base>.png still fade. */
    void videoCutscene(String basePath);
}
