package com.retro.bamfmenu.early.core.secret;

/* The host seam a secret's action acts through -- the provider implements it (like BiosContext). Kept small and
   ADDITIVE: when a new secret needs a new power, add ONE method here and implement it on the provider. core/secret
   defines this interface; bios/ implements it, so core never depends on the presentation host. */
public interface SecretContext {

    /* Flip the dev "Safe For Retro" no-fail / no-score mode (the classic password Easter egg). */
    void toggleSafeForRetro();

    /* Show a one-line message on the BIOS SETUP screen (a secret's confirmation / flavour text). */
    void notice(String message);

    /* Launch a song by its id in the rhythm game -- e.g. a HIDDEN song a secret reveals. Best-effort: shows a notice
       instead of throwing if the id isn't found. */
    void launchSong(String songId);

    /* Launch a hidden mini-game by its id (e.g. "snake"). Best-effort: shows a notice if the id is unknown. The host
       maps the id to the game's screen, so a new hidden game is one case there + one secret here. */
    void launchGame(String gameId);
}
