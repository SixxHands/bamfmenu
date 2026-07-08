package com.retro.bamfmenu.early.core.doom;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import javax.imageio.ImageIO;

/* DEV-ONLY (gradlew :earlywindow:genDoomHud, java.awt.headless=true): rasterise the DOOM status bar (DoomHudLayout) to
   PNGs at the real native cell grid (cw=13 ls=28), with sample HUD states, so the bar -- the big FIGlet numbers + the
   small panel text -- can be eyeballed offline before it's wired into DoomScreen. Never referenced by the mod. */
public final class DoomHudProbe {

    private static final int CW = 13, LS = 28, COLS = 107;

    public static void main(String[] args) throws Exception {
        System.out.println("[hudprobe] small font loaded: " + (Figlet.font("small") != null));
        // START: pistol, full health, no armor, no extra weapons/keys.
        DoomHudLayout.Hud start = new DoomHudLayout.Hud(100, 0, 50, true, "PISTOL",
                new boolean[]{true, true, false, false, false, false, false, false},
                new int[]{50, 0, 0, 0}, new int[]{200, 50, 300, 50}, new boolean[6]);
        // LOADED: mid-fight (matches the reference screenshot: HP 86 ARM 187, BULL 400/400 SHEL 98/100 RCKT 100/100 CELL 600/600).
        DoomHudLayout.Hud loaded = new DoomHudLayout.Hud(86, 187, 98, true, "SHOTGUN",
                new boolean[]{true, true, true, true, true, true, true, true},
                new int[]{400, 98, 600, 100}, new int[]{400, 100, 600, 100},
                new boolean[]{true, true, true, false, false, false}); // blue + yellow + red keys
        render(loaded, COLS, 8, "small", "doom-hud-loaded.png");
        render(start, COLS, 8, "small", "doom-hud-start.png");
    }

    static void render(DoomHudLayout.Hud h, int cols, int rows, String numFont, String file) throws Exception {
        BufferedImage img = new BufferedImage(cols * CW, rows * LS, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, LS - 8));
        int baseline = LS - 7;
        List<DoomHudLayout.Op> ops = DoomHudLayout.layout(h, cols, rows, numFont);
        for (DoomHudLayout.Op op : ops) {
            g.setColor(colour(op.kind()));
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
        System.out.println("[hudprobe] wrote " + out.getAbsolutePath() + " (" + cols + "x" + rows + ") ops=" + ops.size());
    }

    private static Color colour(DoomHudLayout.Kind k) {
        return switch (k) {
            case BIGNUM -> new Color(255, 64, 48);     // DOOM red
            case LABEL -> new Color(255, 200, 40);      // gold
            case ARMS_ON -> Color.WHITE;
            case ARMS_OFF -> new Color(80, 80, 80);
            case STAT -> new Color(205, 205, 205);
            case KEY_BLUE -> new Color(70, 130, 255);
            case KEY_YELLOW -> new Color(255, 225, 50);
            case KEY_RED -> new Color(255, 70, 60);
            case BORDER -> new Color(120, 100, 50);
        };
    }
}
