package com.retro.bamfmenu.early.core.secret;

/* What a secret DOES when entered, given the host seam -- "do whatever": toggle a mode, show a notice, launch a
   hidden song, etc. A functional interface so a secret def writes it as a one-line lambda: `ctx -> ctx.notice(...)`. */
@FunctionalInterface
public interface SecretAction {
    void run(SecretContext ctx);
}
