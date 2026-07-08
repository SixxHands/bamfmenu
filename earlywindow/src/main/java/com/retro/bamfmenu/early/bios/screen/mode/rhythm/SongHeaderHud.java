package com.retro.bamfmenu.early.bios.screen.mode.rhythm;

import com.retro.bamfmenu.early.bios.gfx.TerminalCanvas;

/* The rhythm UI's song header -- the song NAME line + the 3-line scrubbing waveform -- as a standalone,
   OPTIONAL, callable widget so a bespoke SPECIAL mode can reuse the familiar BAMFBEAT header without pulling
   in the whole rhythm renderer. Deliberately a COPY of RhythmRenderer's inline
   header (not a shared call) so the special version can evolve its own look + placement later, and so a
   special simply NOT calling it can never crash by its absence. Pure: draws only through TerminalCanvas and
   holds no state; the caller passes the label + the waveform samples + the play fraction. */
public final class SongHeaderHud {

    private SongHeaderHud() {
    }

    /* Draw the header at left pixel x: the label (gold, clipped to `cols` columns) at row labelY, then the
       3-row '.'/':' waveform at row waveY with the played portion (left of playFrac, 0..1) lit gold and the
       rest dim. A null/empty waveform draws a flat (blank) band -- never throws. */
    public static void render(TerminalCanvas c, int x, int labelY, int waveY, int cols, String label, float[] waveform, float playFrac) {
        c.text(x, labelY, TerminalCanvas.clip(label == null ? "" : label, cols), c.cGold());
        drawWaveform(c, x, waveY, cols, waveform == null ? new float[0] : waveform, playFrac);
    }

    /* A 3-line waveform of the whole clip from '.' (half cell) and ':' (full cell), bottom-aligned so each
       column is a little bar (0..6 half-units = 3 rows x 2). Copied verbatim from the rhythm renderer so this
       widget is self-contained. */
    private static void drawWaveform(TerminalCanvas c, int x, int yTop, int cols, float[] wf, float playFrac) {
        if (cols < 1) {
            return;
        }
        int playCol = Math.max(0, Math.min(cols, Math.round(playFrac * cols)));
        char[][] grid = new char[3][cols];
        for (char[] row : grid) {
            java.util.Arrays.fill(row, ' ');
        }
        for (int col = 0; col < cols; col++) {
            int h = Math.round(sampleWaveform(wf, col, cols) * 6f);
            grid[2][col] = h >= 2 ? ':' : (h >= 1 ? '.' : ' ');
            grid[1][col] = h >= 4 ? ':' : (h >= 3 ? '.' : ' ');
            grid[0][col] = h >= 6 ? ':' : (h >= 5 ? '.' : ' ');
        }
        for (int r = 0; r < 3; r++) {
            String row = new String(grid[r]);
            int split = Math.min(playCol, row.length());
            c.text(x, yTop + r * c.lineSpacing(), row.substring(0, split), c.cGold());
            c.text(x + split * c.charWidth(), yTop + r * c.lineSpacing(), row.substring(split), c.cDim());
        }
    }

    private static float sampleWaveform(float[] wf, int col, int cols) {
        if (wf.length == 0) {
            return 0f;
        }
        int idx = (int) ((long) col * wf.length / Math.max(1, cols));
        if (idx < 0) {
            idx = 0;
        } else if (idx >= wf.length) {
            idx = wf.length - 1;
        }
        return wf[idx];
    }
}
