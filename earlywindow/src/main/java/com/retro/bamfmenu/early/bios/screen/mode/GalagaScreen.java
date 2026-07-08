package com.retro.bamfmenu.early.bios.screen.mode;

import com.retro.bamfmenu.early.bios.Mode;
import com.retro.bamfmenu.early.bios.gfx.TerminalCanvas;
import com.retro.bamfmenu.early.bios.screen.BiosContext;
import com.retro.bamfmenu.early.bios.screen.Screen;
import com.retro.bamfmenu.early.core.asset.Art;
import com.retro.bamfmenu.early.core.galaga.GalagaGame;

import java.util.Queue;

/* Mode.GALAGA host: the HIDDEN Galaga game (revealed by typing the "galaga" secret in the Safe-For-Retro box). Same
   self-contained-game pattern as SnakeScreen/TetrisScreen, with two differences the shooter needs: (1) input is
   {key, action} (PRESS/RELEASE) so left/right are HELD state and fire is per-press -- the provider enqueues both for
   GALAGA; (2) the game is time-stepped, so step() passes the real frame delta to GalagaGame.tick(dt) for smooth
   motion (the formation sway, dives, the capture beam). Drawn on the terminal in ASCII; ESC backs out to BIOS. */
public final class GalagaScreen implements Screen {

    private static final int KEY_ESCAPE = 256, KEY_ENTER = 257, KEY_KP_ENTER = 335;
    private static final int KEY_LEFT = 263, KEY_RIGHT = 262, KEY_UP = 265, KEY_SPACE = 32;
    private static final int ACTION_PRESS = 1; // GLFW_PRESS (RELEASE = 0; only PRESS/RELEASE are enqueued for GALAGA)

    private final Queue<int[]> input;
    private GalagaGame game;
    private long lastNanos;

    public GalagaScreen(Queue<int[]> input) {
        this.input = input;
    }

    @Override
    public Mode mode() {
        return Mode.GALAGA;
    }

    @Override
    public void onEnter(BiosContext ctx) {
        input.clear();
        game = new GalagaGame();
        lastNanos = System.nanoTime();
        ctx.enterMode(Mode.GALAGA);
    }

    @Override
    public void onExit(BiosContext ctx) {
        exit(ctx);
    }

    @Override
    public void step(BiosContext ctx) {
        GalagaGame g = game;
        if (g == null) {
            exit(ctx);
            return;
        }
        int[] ev;
        while ((ev = input.poll()) != null) {
            int key = ev[0];
            boolean press = ev[1] == ACTION_PRESS;
            if (key == KEY_ESCAPE) {
                if (press) {
                    exit(ctx); // ESC always backs out to BIOS (no pause overlay for the Easter egg)
                    return;
                }
                continue;
            }
            if (g.gameOver) {
                if (press && (key == KEY_ENTER || key == KEY_KP_ENTER)) {
                    g.reset();
                }
                continue;
            }
            switch (key) {
                case KEY_LEFT -> g.setLeft(press);
                case KEY_RIGHT -> g.setRight(press);
                case KEY_SPACE, KEY_UP -> {
                    if (press) {
                        g.fire();
                    }
                }
                default -> { }
            }
        }
        long now = System.nanoTime();
        double dt = (now - lastNanos) / 1_000_000_000.0;
        lastNanos = now;
        g.tick(dt);
    }

    /* The host calls this every frame the F2 volume menu freezes the game. While the overlay is up, key events are
       routed to it (PRESS-only), so the RELEASE of a held arrow is swallowed -- dropping held movement here means a
       ship that was moving when the menu opened won't keep drifting after it closes. Fire is per-press (nothing held
       to clear) and the input queue is routed away while frozen, so it can't accumulate. lastNanos is also reset so
       the first frame after resuming doesn't integrate the whole paused span as one giant dt. */
    public void clearHeld() {
        GalagaGame g = game;
        if (g != null) {
            g.setLeft(false);
            g.setRight(false);
        }
        lastNanos = System.nanoTime();
    }

    @Override
    public String controlHints(BiosContext ctx) {
        return "<- -> Move      SPACE Fire      ENTER Retry      ESC Back";
    }

    /* Leave Galaga back to BIOS SETUP, landing on the "Safe For Retro Mode" row (index 2) where the secret box lives. */
    private void exit(BiosContext ctx) {
        int score = game != null ? game.score : 0;
        game = null;
        input.clear();
        ctx.exitToSetup(2, "Galaga: " + score);
    }

    @Override
    public void render(TerminalCanvas c, BiosContext ctx) {
        GalagaGame g = game;
        if (g == null) {
            return; // exit ran earlier this frame; the cleared background is already correct
        }
        int needRows = GalagaGame.FIELD_H + 6;  // title + rule + field (+borders) + HUD + margins
        int needCols = GalagaGame.FIELD_W + 2;
        int byHeight = (int) Math.ceil((double) needRows * c.lineSpacing() * c.fbWidth() / Math.max(1, c.fbHeight()));
        int byWidth = needCols * c.charWidth();
        int logicalW = Math.max(c.logicalW(), Math.max(byHeight, byWidth));
        int logicalH = Math.max(1, logicalW * c.fbHeight() / Math.max(1, c.fbWidth()));
        int across = Math.max(1, logicalW / c.charWidth());

        c.beginFrameShader(logicalW, logicalH);

        int border = c.cFg();
        int fieldChars = GalagaGame.FIELD_W + 2; // "|" + FIELD_W cells + "|"
        int blockX = Math.max(c.margin(), (across - fieldChars) / 2 * c.charWidth());
        int innerX = blockX + c.charWidth();
        int ls = c.lineSpacing();

        int titleY = ls;
        c.text(blockX, titleY, "GALAGA", c.cGold());
        c.text(blockX, titleY + ls, TerminalCanvas.rule(fieldChars), border);

        int gridTop = titleY + ls * 3;
        String hbar = "+" + "-".repeat(GalagaGame.FIELD_W) + "+";
        c.text(blockX, gridTop, hbar, border);
        for (int r = 0; r < GalagaGame.FIELD_H; r++) {
            c.text(blockX, gridTop + (r + 1) * ls, "|", border);
            c.text(blockX + (GalagaGame.FIELD_W + 1) * c.charWidth(), gridTop + (r + 1) * ls, "|", border);
        }
        c.text(blockX, gridTop + (GalagaGame.FIELD_H + 1) * ls, hbar, border);
        int top = gridTop + ls; // row 0 of the field

        // capture beams (drawn under the enemies): a dim column under EACH beaming boss down to the player row, so
        // two bosses beaming at once each show their own beam (the shared beamActive/beamX scalars only tracked one).
        for (GalagaGame.Enemy e : g.enemies) {
            if (e.alive && e.beaming) {
                for (double y = e.y + 1; y < GalagaGame.FIELD_H - 1; y += 1.0) {
                    plot(c, innerX, top, e.x, y, ":", c.cDim());
                }
            }
        }

        // enemies + any held captive
        for (GalagaGame.Enemy e : g.enemies) {
            if (!e.alive) {
                continue;
            }
            plot(c, innerX, top, e.x, e.y, glyphFor(e.type), colorFor(c, e.type));
            if (e.holdsCaptive) {
                plot(c, innerX, top, g.captiveX(e), g.captiveY(e), "/^\\", c.cGold());
            }
        }

        // bullets
        for (double[] b : g.playerBullets) {
            plot(c, innerX, top, b[0], b[1], "|", c.cBright());
        }
        for (double[] b : g.enemyBullets) {
            plot(c, innerX, top, b[0], b[1], ";", Art.LANE_COLORS[3]);
        }

        // the captive being hauled up the beam
        if (g.captured) {
            plot(c, innerX, top, g.captiveX, g.captiveY, "/^\\", c.cGold());
        }

        // player ship(s): hidden while captured; blinks (~8 Hz) through the brief post-death respawn window so a
        // death/invulnerability reads visibly instead of the ship just popping back solid at centre field.
        double respawn = g.respawnRemaining();
        boolean show = respawn <= 0 || (((int) Math.floor(respawn * 8)) % 2 == 0);
        if (!g.captured && show) {
            int playerRow = GalagaGame.FIELD_H - 2;
            if (g.dual) {
                plot(c, innerX, top, g.playerX - 1.5, playerRow, "/^\\", c.cBright());
                plot(c, innerX, top, g.playerX + 1.5, playerRow, "/^\\", c.cBright());
            } else {
                plot(c, innerX, top, g.playerX, playerRow, "/^\\", c.cBright());
            }
        }

        // HUD
        int statY = gridTop + (GalagaGame.FIELD_H + 3) * ls;
        c.text(blockX, statY, "SCORE " + g.score + "    WAVE " + g.wave + "    SHIPS " + "A ".repeat(Math.max(0, g.lives)), c.cDim());

        if (g.interludeTimer > 0 && g.score >= 0 && g.wave >= 1 && !g.gameOver) {
            String banner = "STAGE " + g.wave;
            int bx = blockX + (fieldChars - banner.length()) / 2 * c.charWidth();
            c.text(bx, gridTop + (GalagaGame.FIELD_H / 2 + 1) * ls, banner, c.cGold());
        }

        if (g.gameOver) {
            c.popup(logicalW, logicalH,
                    new String[]{"", "GAME OVER", "Score: " + g.score + "   Wave: " + g.wave, "", "[ENTER] Retry    [ESC] Back", ""},
                    new int[]{c.cBright(), c.cGold(), c.cBright(), c.cBright(), c.cFg(), c.cBright()});
        }
    }

    /* Plot a glyph centered on field cell (cx, cy), clamped to stay inside the field. */
    private static void plot(TerminalCanvas c, int innerX, int top, double cx, double cy, String s, int color) {
        int row = (int) Math.round(cy);
        if (row < 0 || row >= GalagaGame.FIELD_H) {
            return;
        }
        int col = (int) Math.round(cx) - (s.length() - 1) / 2;
        // Fully past either edge -> draw nothing, so a swooping diver flies OFF-screen instead of pinning to the wall.
        if (col + s.length() <= 0 || col >= GalagaGame.FIELD_W) {
            return;
        }
        // Partly off -> draw only the in-field slice (a glyph slides off the edge cell by cell, no smear on the border).
        int from = Math.max(0, -col);
        int to = Math.min(s.length(), GalagaGame.FIELD_W - col);
        c.text(innerX + (col + from) * c.charWidth(), top + row * c.lineSpacing(), s.substring(from, to), color);
    }

    private static String glyphFor(int type) {
        return switch (type) {
            case GalagaGame.BOSS -> "<@>";
            case GalagaGame.BUTTERFLY -> ">W<";
            default -> "vov";
        };
    }

    private static int colorFor(TerminalCanvas c, int type) {
        return switch (type) {
            case GalagaGame.BOSS -> Art.LANE_COLORS[0];
            case GalagaGame.BUTTERFLY -> c.cGold();
            default -> Art.LANE_COLORS[2];
        };
    }
}
