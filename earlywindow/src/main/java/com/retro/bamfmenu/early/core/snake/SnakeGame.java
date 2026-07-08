package com.retro.bamfmenu.early.core.snake;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/* The hidden Snake game's pure logic (headless, no GL/Minecraft -- the SnakeScreen presentation reads this + draws
   it, same core/bios split as TetrisGame/TetrisScreen). A snake on a COLS x ROWS grid: it advances one cell each
   tick() in the current direction; eating the food grows it + scores; hitting a wall or itself ends the run. Input
   is just a direction (handleDir), with a 180-degree reversal rejected. Render thread only -- nothing synchronized. */
public final class SnakeGame {

    public static final int COLS = 28;
    public static final int ROWS = 16;

    /* GLFW key codes inlined so core stays free of the LWJGL import (the TetrisGame convention). */
    private static final int KEY_UP = 265, KEY_DOWN = 264, KEY_LEFT = 263, KEY_RIGHT = 262;

    /* The snake's cells as {row, col}, HEAD first / tail last. */
    public final Deque<int[]> body = new ArrayDeque<>();
    public int foodR;
    public int foodC;
    public int score;
    public boolean gameOver;

    private int dirR;            // current step delta (applied each tick)
    private int dirC;
    private int nextDirR;        // the pending direction from input (applied at the next tick)
    private int nextDirC;
    private final Random rng = new Random();

    public SnakeGame() {
        reset();
    }

    /* (Re)start: a 3-cell snake mid-left moving right, score 0, fresh food. */
    public void reset() {
        body.clear();
        int r = ROWS / 2;
        int c = COLS / 3;
        body.addFirst(new int[]{r, c});       // head
        body.addLast(new int[]{r, c - 1});
        body.addLast(new int[]{r, c - 2});
        dirR = 0;
        dirC = 1;
        nextDirR = 0;
        nextDirC = 1;
        score = 0;
        gameOver = false;
        placeFood();
    }

    public boolean isPlaying() {
        return !gameOver;
    }

    /* Queue a direction from an arrow key (no-op for other keys; a direct 180-degree reversal is rejected so you
       can't instantly fold back into your own neck). Takes effect on the next tick. */
    public void handleDir(int key) {
        int nr = nextDirR;
        int nc = nextDirC;
        switch (key) {
            case KEY_UP -> { nr = -1; nc = 0; }
            case KEY_DOWN -> { nr = 1; nc = 0; }
            case KEY_LEFT -> { nr = 0; nc = -1; }
            case KEY_RIGHT -> { nr = 0; nc = 1; }
            default -> { return; }
        }
        if (nr == -dirR && nc == -dirC) {
            return; // reversing straight back onto yourself -- ignore
        }
        nextDirR = nr;
        nextDirC = nc;
    }

    /* Advance one cell. Wall or self collision ends the run; landing on the food grows the snake (tail kept) +
       scores + respawns the food; otherwise the tail is dropped so the length holds. */
    public void tick() {
        if (gameOver) {
            return;
        }
        dirR = nextDirR;
        dirC = nextDirC;
        int[] head = body.peekFirst();
        int nr = head[0] + dirR;
        int nc = head[1] + dirC;
        if (nr < 0 || nr >= ROWS || nc < 0 || nc >= COLS || hits(nr, nc, true)) {
            gameOver = true;
            return;
        }
        body.addFirst(new int[]{nr, nc});
        if (nr == foodR && nc == foodC) {
            score++;
            placeFood();
        } else {
            body.removeLast();
        }
    }

    /* True if {r,c} is on the snake. `ignoreTail` excludes the current tail cell, which moves away this tick (so you
       may chase the spot it just vacated without a false self-collision). */
    private boolean hits(int r, int c, boolean ignoreTail) {
        int[] tail = ignoreTail ? body.peekLast() : null;
        for (int[] cell : body) {
            if (cell == tail) {
                continue;
            }
            if (cell[0] == r && cell[1] == c) {
                return true;
            }
        }
        return false;
    }

    /* Drop the food on a random empty cell. If the snake fills the board (a win!), leave it on the head -- harmless,
       and effectively unreachable at this grid size. */
    private void placeFood() {
        if (body.size() >= ROWS * COLS) {
            return;
        }
        int r;
        int c;
        do {
            r = rng.nextInt(ROWS);
            c = rng.nextInt(COLS);
        } while (hits(r, c, false));
        foodR = r;
        foodC = c;
    }
}
