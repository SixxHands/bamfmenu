package com.retro.bamfmenu.early.core.tools.probe;

import com.retro.bamfmenu.early.core.asset.AsciiImage;
import com.retro.bamfmenu.early.core.asset.VideoClip;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/* DEV-ONLY (gradlew :earlywindow:genWysi, java.awt.headless=true): rasterise the wysi image cutscene's tooltip-cell
   overlay to PNGs at three timeline points (mid fade-in / hold / mid fade-out), through the SAME AsciiImage render +
   fade the provider uses, at the real native cell metrics, so the auto-scale, centering, and fade curve can be
   eyeballed offline before a boot. Draws the surrounding cell frame for context. Never referenced by the mod. */
public final class AsciiImageProbe {

    // Real native cell metrics (cw=13 ls=28); the setup screen's right cell is ~67 cols x ~15 interior rows.
    private static final int CW = 13, LS = 28, CELL_COLS = 67, CELL_ROWS = 15;

    public static void main(String[] args) throws Exception {
        AsciiImage.Source src = AsciiImage.load("/wysi.png");
        System.out.println("[wysiprobe] /wysi.png loaded: " + (src != null));
        if (src == null) {
            System.exit(1);
        }
        // t=0.5s -> early fade-in (f=0.25); t=2.5s -> hold (f=1); t=3.6s -> fade-out (f=0.7)
        render(src, 0.25, "wysi-fade-in.png");
        render(src, 1.00, "wysi-hold.png");
        render(src, 0.70, "wysi-fade-out.png");
        AsciiImage.Rendered r = AsciiImage.render(src, CELL_COLS, CELL_ROWS, CW, LS);
        System.out.println("[wysiprobe] auto-fit: " + r.cols() + "x" + r.rows() + " cells (cell box "
                + CELL_COLS + "x" + CELL_ROWS + ")");

        // The VIDEO cutscene: verify the bundled clip loads headless + dump a mid-clip frame as the 3x block-pixel
        // render (the shipping mode) so the legibility can be eyeballed offline.
        VideoClip clip = VideoClip.load("/wysi", 15.0);
        if (clip == null) {
            System.out.println("[wysiprobe] video clip: MISSING (/wysi/f00.jpg..) -- image fallback would be used");
        } else {
            System.out.printf("[wysiprobe] video clip: %d frames, %.2fs%n", clip.frameCount(), clip.durationSeconds());
            renderBlocks(clip.frame(clip.durationSeconds() / 2), 3, "wysi-video-mid.png");
        }
    }

    /** The video mode: block-pixel cells at `d` x density in the same tooltip box (fillRect per cell; the terminal
        draws a colored '█' glyph, near-identical coverage). */
    static void renderBlocks(AsciiImage.Source src, int d, String file) throws Exception {
        BufferedImage img = new BufferedImage((CELL_COLS + 2) * CW, (CELL_ROWS + 2) * LS, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        double cw = (double) CW / d, ls = (double) LS / d;
        AsciiImage.Rendered a = AsciiImage.render(src, CELL_COLS * d, CELL_ROWS * d, CW, LS);
        int x0 = CW + (int) (Math.max(0, (CELL_COLS * d - a.cols()) / 2) * cw);
        int y0 = LS + (int) (Math.max(0, (CELL_ROWS * d - a.rows()) / 2) * ls);
        for (int y = 0; y < a.rows(); y++) {
            for (int x = 0; x < a.cols(); x++) {
                int abgr = a.colors()[y * a.cols() + x];
                g.setColor(new Color(abgr & 0xFF, (abgr >> 8) & 0xFF, (abgr >> 16) & 0xFF));
                g.fillRect(x0 + (int) (x * cw), y0 + (int) (y * ls), (int) Math.ceil(cw), (int) Math.ceil(ls));
            }
        }
        g.dispose();
        File out = new File(file);
        ImageIO.write(img, "png", out);
        System.out.println("[wysiprobe] wrote " + out.getAbsolutePath() + " grid=" + a.cols() + "x" + a.rows());
    }

    static void render(AsciiImage.Source src, double fade, String file) throws Exception {
        int cols = CELL_COLS + 2, rows = CELL_ROWS + 2; // + the frame
        BufferedImage img = new BufferedImage(cols * CW, rows * LS, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, LS - 8));
        int baseline = LS - 7;
        g.setColor(new Color(190, 190, 190)); // the cell frame, for context
        for (int c = 0; c < cols; c++) {
            g.drawString(c == 0 ? "┌" : c == cols - 1 ? "┐" : "─", c * CW, baseline);
            g.drawString(c == 0 ? "└" : c == cols - 1 ? "┘" : "─", c * CW, (rows - 1) * LS + baseline);
        }
        for (int r = 1; r < rows - 1; r++) {
            g.drawString("│", 0, r * LS + baseline);
            g.drawString("│", (cols - 1) * CW, r * LS + baseline);
        }
        AsciiImage.Rendered a = AsciiImage.render(src, CELL_COLS, CELL_ROWS, CW, LS, fade);
        int x0 = 1 + Math.max(0, (CELL_COLS - a.cols()) / 2);
        int y0 = 1 + Math.max(0, (CELL_ROWS - a.rows()) / 2);
        for (int y = 0; y < a.rows(); y++) {
            for (int x = 0; x < a.cols(); x++) {
                char ch = a.glyphs()[y * a.cols() + x];
                if (ch == ' ') {
                    continue;
                }
                int abgr = a.colors()[y * a.cols() + x];
                g.setColor(new Color(abgr & 0xFF, (abgr >> 8) & 0xFF, (abgr >> 16) & 0xFF)); // ABGR -> RGB
                g.drawString(String.valueOf(ch), (x0 + x) * CW, (y0 + y) * LS + baseline);
            }
        }
        g.dispose();
        File out = new File(file);
        ImageIO.write(img, "png", out);
        System.out.println("[wysiprobe] wrote " + out.getAbsolutePath() + " fade=" + fade);
    }

    private AsciiImageProbe() {
    }
}
