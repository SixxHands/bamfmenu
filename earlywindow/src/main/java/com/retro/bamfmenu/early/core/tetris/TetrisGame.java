package com.retro.bamfmenu.early.core.tetris;

import java.util.Random;

/* Self-contained terminal Tetris for the BAMFOS "Enable Mechanics" option. Pure logic + state: no GL,
   no Minecraft, no threading primitives, so the early-window jar stays a plain service.

   Threading contract: the provider owns one instance and touches it from exactly ONE thread, the
   render thread. It drains queued GLFW key codes into handleKey(...), calls tick() once per frame, then
   reads the public fields to draw. Because every access is on that single thread, nothing here is
   synchronized; the only cross-thread channel is the provider's ConcurrentLinkedQueue feeding handleKey.

   Rotation uses explicit four-state offset tables (not SRS pivot math) plus a simple horizontal wall
   kick: compact and hard to get wrong for a proof of concept. Gravity is tick-based: the provider
   ticks at ~20fps, so a "tick" is one frame and fall speed is expressed in frames-per-cell by level. */
public final class TetrisGame {

    public static final int COLS = 10;
    public static final int ROWS = 20;

    /* Returned from handleKey so the provider can react without reaching into our internals. */
    public static final int SIGNAL_NONE = 0;
    public static final int SIGNAL_QUIT_TO_BIOS = 1;

    /* GLFW key codes, duplicated here so this class stays free of the LWJGL import. These are stable
       GLFW constants. */
    private static final int KEY_SPACE = 32;
    private static final int KEY_ESCAPE = 256;
    private static final int KEY_ENTER = 257;
    private static final int KEY_RIGHT = 262;
    private static final int KEY_LEFT = 263;
    private static final int KEY_DOWN = 264;
    private static final int KEY_UP = 265;
    private static final int KEY_KP_ENTER = 335;

    /* Seven tetrominoes, four rotation states each, four (row,col) cells per state within a 4x4 box.
       Ids: 1=I 2=O 3=T 4=S 5=Z 6=J 7=L (0 = empty board cell). Index here is id-1. Orientations follow
       the guideline spawn-flat-side-first; the O piece repeats its single shape and never rotates. */
    private static final int[][][][] SHAPES = {
        { /* I */
            {{1,0},{1,1},{1,2},{1,3}},
            {{0,2},{1,2},{2,2},{3,2}},
            {{2,0},{2,1},{2,2},{2,3}},
            {{0,1},{1,1},{2,1},{3,1}},
        },
        { /* O */
            {{0,1},{0,2},{1,1},{1,2}},
            {{0,1},{0,2},{1,1},{1,2}},
            {{0,1},{0,2},{1,1},{1,2}},
            {{0,1},{0,2},{1,1},{1,2}},
        },
        { /* T */
            {{0,1},{1,0},{1,1},{1,2}},
            {{0,1},{1,1},{1,2},{2,1}},
            {{1,0},{1,1},{1,2},{2,1}},
            {{0,1},{1,0},{1,1},{2,1}},
        },
        { /* S */
            {{0,1},{0,2},{1,0},{1,1}},
            {{0,1},{1,1},{1,2},{2,2}},
            {{1,1},{1,2},{2,0},{2,1}},
            {{0,0},{1,0},{1,1},{2,1}},
        },
        { /* Z */
            {{0,0},{0,1},{1,1},{1,2}},
            {{0,2},{1,1},{1,2},{2,1}},
            {{1,0},{1,1},{2,1},{2,2}},
            {{0,1},{1,0},{1,1},{2,0}},
        },
        { /* J */
            {{0,0},{1,0},{1,1},{1,2}},
            {{0,1},{0,2},{1,1},{2,1}},
            {{1,0},{1,1},{1,2},{2,2}},
            {{0,1},{1,1},{2,0},{2,1}},
        },
        { /* L */
            {{0,2},{1,0},{1,1},{1,2}},
            {{0,1},{1,1},{2,1},{2,2}},
            {{1,0},{1,1},{1,2},{2,0}},
            {{0,0},{0,1},{1,1},{2,1}},
        },
    };

    /* Frames per cell by level (index = level, clamped to 10). 20fps, so level 1 ~= 0.9s/cell. */
    private static final int[] GRAVITY = {18, 18, 14, 11, 8, 6, 5, 4, 3, 2, 1};
    private static final int[] LINE_SCORE = {0, 100, 300, 500, 800};
    private static final int MAX_LEVEL = 10;

    // public state read by the renderer (single-threaded access, see class doc)
    public final int[][] cells = new int[ROWS][COLS];
    public int score;
    public int level = 1;
    public int lines;
    public int id;            // active piece id (1..7)
    public int rot;           // active rotation index (0..3)
    public int px;            // active box left column on the board
    public int py;            // active box top row on the board
    public int next;          // next piece id for the preview
    public boolean gameOver;
    public boolean paused;
    public boolean pauseSelYes;   // false = No (resume) highlighted, true = Yes (quit) highlighted

    private final Random rng = new Random(System.nanoTime());
    private final int[] bag = new int[7];
    private int bagPos = 7;     // forces a refill on first draw
    private int tickCount;

    public TetrisGame() {
        next = nextFromBag();
        spawn();
    }

    /* True while the game wants gravity ticks. */
    public boolean isPlaying() {
        return !paused && !gameOver;
    }

    /* Advance gravity by one frame. No-op while paused or over. */
    public void tick() {
        if (!isPlaying()) {
            return;
        }
        tickCount++;
        if (tickCount >= GRAVITY[Math.min(level, MAX_LEVEL)]) {
            tickCount = 0;
            stepDown();
        }
    }

    /* Apply one queued key. Interpretation depends on the current sub-state (playing / paused / over). */
    public int handleKey(int key) {
        if (gameOver) {
            if (key == KEY_ENTER || key == KEY_KP_ENTER || key == KEY_ESCAPE) {
                return SIGNAL_QUIT_TO_BIOS;
            }
            return SIGNAL_NONE;
        }
        if (paused) {
            switch (key) {
                case KEY_UP, KEY_LEFT -> pauseSelYes = false;   // highlight No (resume)
                case KEY_DOWN, KEY_RIGHT -> pauseSelYes = true; // highlight Yes (quit)
                case KEY_ENTER, KEY_KP_ENTER -> {
                    if (pauseSelYes) {
                        return SIGNAL_QUIT_TO_BIOS;
                    }
                    paused = false; // No -> resume
                }
                case KEY_ESCAPE -> paused = false; // Esc also resumes
                default -> { }
            }
            return SIGNAL_NONE;
        }
        switch (key) {
            case KEY_LEFT -> move(-1);
            case KEY_RIGHT -> move(1);
            case KEY_DOWN -> softDrop();
            case KEY_UP -> rotate();
            case KEY_SPACE -> hardDrop();
            case KEY_ESCAPE -> {
                paused = true;
                pauseSelYes = false;
            }
            default -> { }
        }
        return SIGNAL_NONE;
    }

    // piece mechanics

    private void move(int dx) {
        if (!collides(id, rot, px + dx, py)) {
            px += dx;
        }
    }

    private void rotate() {
        if (id == 2) {
            return; // O does not rotate
        }
        int nr = (rot + 1) % 4;
        int[] kicks = {0, -1, 1, -2, 2}; // try in place, then nudge horizontally (simple wall kick)
        for (int k : kicks) {
            if (!collides(id, nr, px + k, py)) {
                rot = nr;
                px += k;
                return;
            }
        }
        // no kick fit: keep the current orientation
    }

    private void softDrop() {
        if (!collides(id, rot, px, py + 1)) {
            py++;
            score++;
            tickCount = 0; // a manual drop resets the gravity clock so it does not double-step this frame
        } else {
            lockPiece();
        }
    }

    private void hardDrop() {
        while (!collides(id, rot, px, py + 1)) {
            py++;
            score += 2;
        }
        lockPiece();
    }

    /* Gravity step: fall one row, or lock if blocked. */
    private void stepDown() {
        if (!collides(id, rot, px, py + 1)) {
            py++;
        } else {
            lockPiece();
        }
    }

    private void lockPiece() {
        for (int[] cell : SHAPES[id - 1][rot]) {
            int r = py + cell[0];
            int c = px + cell[1];
            if (r >= 0 && r < ROWS && c >= 0 && c < COLS) {
                cells[r][c] = id;
            }
        }
        clearLines();
        spawn();
    }

    private void clearLines() {
        int cleared = 0;
        for (int r = ROWS - 1; r >= 0; r--) {
            if (rowFull(r)) {
                collapseInto(r);
                cleared++;
                r++; // re-test this row, which now holds what fell from above
            }
        }
        if (cleared > 0) {
            score += LINE_SCORE[cleared] * level;
            lines += cleared;
            level = Math.min(MAX_LEVEL, 1 + lines / 10);
        }
    }

    private boolean rowFull(int r) {
        for (int c = 0; c < COLS; c++) {
            if (cells[r][c] == 0) {
                return false;
            }
        }
        return true;
    }

    /* Shift every row above r down by one into r, then clear the top row. */
    private void collapseInto(int r) {
        for (int rr = r; rr > 0; rr--) {
            System.arraycopy(cells[rr - 1], 0, cells[rr], 0, COLS);
        }
        for (int c = 0; c < COLS; c++) {
            cells[0][c] = 0;
        }
    }

    private void spawn() {
        id = next;
        next = nextFromBag();
        rot = 0;
        px = 3; // centers both the 4-wide I and the 3-wide / 2-wide pieces well enough
        py = 0;
        tickCount = 0;
        if (collides(id, rot, px, py)) {
            gameOver = true; // top-out: the new piece cannot be placed
        }
    }

    /* 7-bag randomizer: deal a shuffled permutation of 1..7, refill when exhausted, for fair piece order. */
    private int nextFromBag() {
        if (bagPos >= 7) {
            for (int i = 0; i < 7; i++) {
                bag[i] = i + 1;
            }
            for (int i = 6; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                int t = bag[i];
                bag[i] = bag[j];
                bag[j] = t;
            }
            bagPos = 0;
        }
        return bag[bagPos++];
    }

    /* True if piece (id,rot) at box origin (x,y) overlaps a wall, the floor, or a locked cell. Rows above
       the top (r < 0) are allowed so a piece may sit partly off-screen; only floor/walls/occupied block. */
    private boolean collides(int id, int rot, int x, int y) {
        for (int[] cell : SHAPES[id - 1][rot]) {
            int r = y + cell[0];
            int c = x + cell[1];
            if (c < 0 || c >= COLS || r >= ROWS) {
                return true;
            }
            if (r >= 0 && cells[r][c] != 0) {
                return true;
            }
        }
        return false;
    }

    // renderer helpers (read-only)

    /* Board cells (row,col) currently covered by the active piece, for the falling-piece overlay. */
    public int[][] activeCells() {
        int[][] out = new int[4][2];
        int[][] shape = SHAPES[id - 1][rot];
        for (int i = 0; i < 4; i++) {
            out[i][0] = py + shape[i][0];
            out[i][1] = px + shape[i][1];
        }
        return out;
    }

    /* The four (row,col) offsets of a piece in a 4x4 box, for drawing the NEXT preview. */
    public static int[][] shape(int id, int rot) {
        return SHAPES[id - 1][rot];
    }
}
