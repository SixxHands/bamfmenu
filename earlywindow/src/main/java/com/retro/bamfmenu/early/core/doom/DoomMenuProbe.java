package com.retro.bamfmenu.early.core.doom;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import javax.imageio.ImageIO;

/* DEV-ONLY (gradlew :earlywindow:genDoomMenu, java.awt.headless=true): rasterise the DOOM menus to PNGs through the
   EXACT same DoomMenuLayout the BIOS uses, at the real measured terminal grid (107x28 cells, cw=13 ls=28), so the menu
   layout (banner font choice, big-item placement, centering, fit) can be eyeballed offline before the user ever boots.
   Each char is blitted into its own fixed cell -- the same way TerminalCanvas lays text out -- so the grid alignment
   matches the game even though the glyph font here is just MONOSPACED. Never referenced by the mod; harmless dead code. */
public final class DoomMenuProbe {

    // Measured native terminal cell grid (printed by the one-shot [doomdims] probe in the dev client).
    private static final int COLS = 107, ROWS = 28, CW = 13, LS = 28;

    public static void main(String[] args) throws Exception {
        System.out.println("[menuprobe] fonts loaded: Poison=" + (Figlet.font("Poison") != null)
                + " Doom=" + (Figlet.font("Doom") != null) + " small=" + (Figlet.font("small") != null));
        // The REAL menu item sets DoomState produces (enumerated from the engine's Menu.java), incl. the game-mode
        // variants and the status==-1 spacer rows ("") in Options/Sound and the dynamic Load/Save "[ EMPTY ]" slots.
        String[][] menus = {
            {"NEW GAME", "OPTIONS", "LOAD GAME", "SAVE GAME", "READ THIS!", "QUIT GAME"},          // main (DOOM1)
            {"NEW GAME", "OPTIONS", "LOAD GAME", "SAVE GAME", "QUIT GAME"},                         // main (DOOM2, no READ THIS!)
            {"KNEE-DEEP IN THE DEAD", "THE SHORES OF HELL", "INFERNO", "THY FLESH CONSUMED"},       // episode (retail, 4)
            {"KNEE-DEEP IN THE DEAD", "THE SHORES OF HELL", "INFERNO"},                             // episode (shareware, 3)
            {"I'M TOO YOUNG TO DIE", "HEY, NOT TOO ROUGH", "HURT ME PLENTY", "ULTRA-VIOLENCE", "NIGHTMARE!"}, // skill
            {"END GAME", "MESSAGES", "GRAPHIC DETAIL", "SCREEN SIZE", "", "MOUSE SENSITIVITY", "", "SOUND VOLUME"}, // options (+2 spacers)
            {"SFX VOLUME", "", "MUSIC VOLUME", ""},                                                 // sound (+2 spacers)
            {"[ EMPTY ]", "[ EMPTY ]", "[ EMPTY ]", "[ EMPTY ]", "[ EMPTY ]", "[ EMPTY ]"},        // load/save (no saves)
            {"E1M1 entryway 12%", "[ EMPTY ]", "[ EMPTY ]", "[ EMPTY ]", "[ EMPTY ]", "[ EMPTY ]"},// save (one named slot)
        };
        String[] names = {"main", "main-doom2", "episode", "episode-sw", "skill", "options", "sound", "loadsave", "loadsave-named"};
        for (int m = 0; m < menus.length; m++) {
            render(menus[m], 0, "doom-menu-" + names[m] + ".png");
        }
    }

    static void render(String[] items, int sel, String file) throws Exception {
        BufferedImage img = new BufferedImage(COLS * CW, ROWS * LS, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, LS - 8));
        int baseline = LS - 7;
        Color gold = new Color(255, 215, 0), white = Color.WHITE; // FG_GOLD / cBright
        List<DoomMenuLayout.Op> ops = DoomMenuLayout.layout(items, sel, COLS, ROWS);
        for (DoomMenuLayout.Op op : ops) {
            g.setColor(switch (op.kind()) { // mirror DoomScreen.drawMenu's exhaustive Kind->colour switch
                case ITEM_SELECTED -> white;
                case BANNER, HEADER, ITEM -> gold;
            });
            String t = op.text();
            for (int i = 0; i < t.length(); i++) {
                char ch = t.charAt(i);
                if (ch != ' ') {
                    g.drawString(String.valueOf(ch), (op.col() + i) * CW, op.row() * LS + baseline);
                }
            }
        }
        g.dispose();
        File out = new File(file);
        ImageIO.write(img, "png", out);
        System.out.println("[menuprobe] wrote " + out.getAbsolutePath() + " items=" + items.length + " ops=" + ops.size());
    }
}
