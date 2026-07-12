package com.retro.bamfmenu.early.core.secret.secrets.galaga.game;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/* The hidden Galaga game's pure logic (headless, no GL/Minecraft; GalagaScreen reads this + draws it, the same
   core/bios split as SnakeGame/SnakeScreen). A full Galaga: a player fixed-shooter at the bottom; an enemy FORMATION
   flies in, sways, and peels off divers that swoop + shoot; boss galagas can fire a TRACTOR BEAM that CAPTURES your
   ship; shoot the boss holding it to rescue the ship into a DUAL FIGHTER (double fire). Waves escalate; 3 lives.

   Coordinates are floats in a FIELD_W x FIELD_H cell space; the renderer snaps them to character cells. Time-stepped:
   tick(dt) integrates by the elapsed seconds the screen passes in. Render thread only, nothing synchronized. */
public final class GalagaGame {

    public static final int FIELD_W = 44;
    public static final int FIELD_H = 26;
    private static final double PLAYER_Y = FIELD_H - 2;
    private static final double PLAYER_SPEED = 24.0;     // cells / sec
    private static final double BULLET_SPEED = 40.0;
    private static final double ENEMY_BULLET_SPEED = 18.0;
    private static final int MAX_PLAYER_BULLETS = 2;     // Galaga's iconic 2-shot cap (x2 while dual)
    private static final double FIRE_COOLDOWN = 0.16;
    private static final double DUAL_OFFSET = 1.5;       // half the gap between the two dual ships

    private static final int FORM_ROWS = 5;
    private static final int FORM_COLS = 8;
    private static final double FORM_SPACING_X = 4.0;
    private static final double FORM_SPACING_Y = 2.2;
    private static final double FORM_TOP = 3.0;
    private static final double SWAY_AMP = 3.0;
    private static final double HOVER_Y = FIELD_H * 0.46; // where a capturing boss parks to beam

    /* Enemy types (top rows are bosses, which alone can capture). */
    public static final int BEE = 0, BUTTERFLY = 1, BOSS = 2;
    /* Enemy path states. */
    private static final int ENTERING = 0, FORMATION = 1, DIVING = 2, RETURNING = 3, BEAMING = 4;

    public static final class Enemy {
        public int type;
        public int slotR, slotC;
        public int state;
        public double x, y;
        public double t;             // 0..1 progress along the current path
        public double startX, startY; // path origin (entry / dive launch)
        public double targetX;       // player x sampled at dive launch (where the swoop aims)
        public double amp, dir;      // swoop amplitude + direction
        public double shootT;        // countdown to the next shot while diving
        public boolean holdsCaptive; // a boss carrying the captured player ship
        public boolean beaming;      // true while this boss is actively firing its tractor beam (renderer draws a beam per boss)
        public boolean alive = true;
    }

    public final List<Enemy> enemies = new ArrayList<>();
    public final List<double[]> playerBullets = new ArrayList<>(); // {x, y}
    public final List<double[]> enemyBullets = new ArrayList<>();   // {x, y}

    public double playerX;
    public int lives;
    public int score;
    public int wave;
    public boolean dual;             // dual fighter (after a rescue)
    public boolean gameOver;

    /* Capture-beam state (read by the renderer). */
    public boolean beamActive;
    public double beamX;
    public double beamTopY;
    public boolean captured;         // the ship is being hauled up; no player control until respawn
    public double captiveX, captiveY; // the captured ship's drawn position (rises to the boss)

    private boolean left, right;
    private double fireCd;
    private double formSway;
    private double diveTimer;
    private double respawnTimer;     // brief no-control window after a death/capture
    public double interludeTimer;    // "STAGE N" pause between waves (public: renderer shows the banner)
    private Enemy captureBoss;       // the boss currently beaming / hauling the captive
    private double captureAnim;      // 0..1 as the captive rises into the boss
    private final Random rng = new Random();

    public GalagaGame() {
        reset();
    }

    public void reset() {
        enemies.clear();
        playerBullets.clear();
        enemyBullets.clear();
        playerX = FIELD_W / 2.0;
        lives = 3;
        score = 0;
        wave = 1;
        dual = false;
        gameOver = false;
        beamActive = false;
        captured = false;
        captureBoss = null;
        captureAnim = 0;
        left = right = false;
        fireCd = 0;
        formSway = 0;
        respawnTimer = 0;
        interludeTimer = 1.4;
        spawnWave();
    }

    public boolean isPlaying() {
        return !gameOver;
    }

    /* Remaining seconds of the brief no-control window after a death (0 when not respawning); the renderer blinks
       the ship over it so a death reads as a death instead of the ship just popping back solid. */
    public double respawnRemaining() {
        return respawnTimer;
    }

    public void setLeft(boolean v) {
        left = v;
    }

    public void setRight(boolean v) {
        right = v;
    }

    /* Fire from the active ship(s), one bullet per ship, capped (the Galaga 2-shot rule, doubled when dual). */
    public void fire() {
        if (gameOver || captured || respawnTimer > 0 || fireCd > 0) {
            return;
        }
        int cap = dual ? MAX_PLAYER_BULLETS * 2 : MAX_PLAYER_BULLETS;
        if (playerBullets.size() >= cap) {
            return;
        }
        if (dual) {
            playerBullets.add(new double[]{playerX - DUAL_OFFSET, PLAYER_Y - 1});
            playerBullets.add(new double[]{playerX + DUAL_OFFSET, PLAYER_Y - 1});
        } else {
            playerBullets.add(new double[]{playerX, PLAYER_Y - 1});
        }
        fireCd = FIRE_COOLDOWN;
    }

    public void tick(double dt) {
        if (gameOver) {
            return;
        }
        if (dt > 0.04) {
            dt = 0.04; // clamp a hitch so even the fastest bullet (40 c/s -> 1.6 cells/tick) steps less than the 2.0-wide hit band, never tunneling
        }
        formSway += dt * 0.7;
        fireCd -= dt;
        if (interludeTimer > 0) {
            interludeTimer -= dt;
        }
        if (respawnTimer > 0) {
            respawnTimer -= dt;
        }

        // player movement (no control while captured / mid-respawn)
        if (!captured && respawnTimer <= 0) {
            double dx = (right ? 1 : 0) - (left ? 1 : 0);
            playerX += dx * PLAYER_SPEED * dt;
            double margin = dual ? DUAL_OFFSET + 2 : 2; // +1 keeps the ship's CENTER on-field; +2 keeps its full 3-wide glyph on-field (so the renderer's edge-clip never trims the ship)
            playerX = clamp(playerX, margin, FIELD_W - margin);
        }

        moveBullets(playerBullets, -BULLET_SPEED * dt);
        moveBullets(enemyBullets, ENEMY_BULLET_SPEED * dt);

        beamActive = false; // re-asserted by a boss in its beam phase below
        for (Enemy e : enemies) {
            if (e.alive) {
                updateEnemy(e, dt);
            }
        }
        if (captured) {
            if (captureBoss == null || !captureBoss.alive) {
                // The abductor was destroyed mid-rise (a shot already in flight caught it before holdsCaptive was
                // set). The capture never completes, so the player keeps the ship and loses NO life; resume control.
                captured = false;
                captureBoss = null;
                respawnTimer = 0;
            } else {
                captureAnim += dt / 0.8;
                captiveY = lerp(PLAYER_Y, captureBoss.y + 1, clamp01(captureAnim));
                captiveX = captureBoss.x;
                if (captureAnim >= 1.0) {
                    finishCapture();
                }
            }
        }

        // launch dives once the wave has settled and the interlude is over
        if (interludeTimer <= 0 && formationSettled()) {
            diveTimer -= dt;
            if (diveTimer <= 0) {
                launchDive();
                diveTimer = Math.max(0.5, 1.8 - wave * 0.12) + rng.nextDouble() * 0.8;
            }
        }

        collide();

        if (aliveCount() == 0 && interludeTimer <= 0) {
            wave++;
            interludeTimer = 1.6; // brief "STAGE N" pause; dual fighter persists across waves
            spawnWave();
        }
    }

    // enemy paths

    private void updateEnemy(Enemy e, double dt) {
        switch (e.state) {
            case ENTERING -> {
                e.t += dt / 1.1;
                double s = smooth(clamp01(e.t));
                e.x = lerp(e.startX, slotX(e), s);
                e.y = lerp(e.startY, slotY(e), s) - Math.sin(s * Math.PI) * 2.0; // a little arc on the way in
                if (e.t >= 1.0) {
                    e.state = FORMATION;
                }
            }
            case FORMATION -> {
                e.x = slotX(e);
                e.y = slotY(e);
            }
            case DIVING -> {
                e.t += dt / 2.3;
                double t = e.t;
                e.y = lerp(e.startY, FIELD_H + 2, t);
                e.x = lerp(e.startX, e.targetX, t) + e.dir * e.amp * Math.sin(t * Math.PI * 2.0);
                e.shootT -= dt;
                if (e.shootT <= 0 && e.y > 1 && e.y < PLAYER_Y - 1) {
                    enemyBullets.add(new double[]{e.x, e.y});
                    e.shootT = 0.45;
                }
                if (e.t >= 1.0) {
                    e.state = RETURNING; // wrap to the top and fly home
                    e.t = 0;
                    e.startX = slotX(e);
                    e.startY = -2;
                }
            }
            case RETURNING -> {
                e.t += dt / 1.0;
                double s = smooth(clamp01(e.t));
                e.x = lerp(e.startX, slotX(e), s);
                e.y = lerp(e.startY, slotY(e), s);
                if (e.t >= 1.0) {
                    e.state = FORMATION;
                }
            }
            case BEAMING -> updateBeamingBoss(e, dt);
            default -> { }
        }
    }

    /* A boss capture run: descend to a hover, fire the tractor beam, then (if it caught nothing) fly home. On a
       catch, startCapture flips the boss to RETURNING so it carries the ship home, so the return arm here only
       runs for a miss. beamActive is re-asserted each tick (cleared at the top of tick), so no manual clear here. */
    private void updateBeamingBoss(Enemy e, double dt) {
        e.t += dt / 3.0;
        double t = e.t;
        if (t < 0.3) {
            e.beaming = false;
            double s = smooth(t / 0.3);
            e.x = lerp(e.startX, e.targetX, s); // descend to the hover point above the player
            e.y = lerp(e.startY, HOVER_Y, s);
        } else if (t < 0.78) {
            e.x = e.targetX;
            e.y = HOVER_Y;
            e.beaming = !captured; // the beam fires only while no one is currently being hauled up
            if (!captured) {
                beamActive = true;
                beamX = e.x;
                beamTopY = e.y + 1;
                if (respawnTimer <= 0 && lives > 0 && Math.abs(playerX - beamX) < 2.5 && captureBoss == null) {
                    startCapture(e); // -> RETURNING, hauling the ship home
                }
            }
        } else {
            e.beaming = false;
            double s = smooth((t - 0.78) / 0.22); // missed -> fly home from the hover to the slot
            e.x = lerp(e.targetX, slotX(e), s);
            e.y = lerp(HOVER_Y, slotY(e), s);
            if (e.t >= 1.0) {
                e.state = FORMATION;
            }
        }
    }

    private void startCapture(Enemy boss) {
        captured = true;
        captureBoss = boss;
        captureAnim = 0;
        captiveX = playerX;
        captiveY = PLAYER_Y;
        beamActive = false;
        boss.beaming = false;
        boss.state = RETURNING; // carry the captive home; holdsCaptive is set when the rise finishes
        boss.t = 0;
        boss.startX = boss.x;
        boss.startY = boss.y;
    }

    /* The captive finished rising into the boss: the player loses that ship; respawn if a life remains, else game over.
       The boss flies home in FORMATION carrying the captive (rescuable). */
    private void finishCapture() {
        captured = false;
        if (captureBoss != null) {
            captureBoss.holdsCaptive = true;
        }
        captureBoss = null;
        dual = false; // a single ship was captured; lose dual if it had it
        lives--;
        if (lives <= 0) {
            gameOver = true;
        } else {
            playerX = FIELD_W / 2.0;
            respawnTimer = 1.0;
        }
    }

    // collisions

    private void collide() {
        // player bullets vs enemies (and a held captive)
        for (Iterator<double[]> bi = playerBullets.iterator(); bi.hasNext(); ) {
            double[] b = bi.next();
            boolean spent = false;
            for (Enemy e : enemies) {
                if (!e.alive) {
                    continue;
                }
                if (e.holdsCaptive && hit(b[0], b[1], captiveX(e), captiveY(e))) {
                    // shot your OWN captured ship -> it is destroyed, no rescue
                    e.holdsCaptive = false;
                    spent = true;
                    break;
                }
                if (hit(b[0], b[1], e.x, e.y)) {
                    boolean rescue = e.holdsCaptive;
                    e.alive = false;
                    score += scoreFor(e);
                    if (rescue) {
                        score += 1000;
                        dual = true; // freed ship docks beside you
                        respawnTimer = 0;
                    }
                    spent = true;
                    break;
                }
            }
            if (spent) {
                bi.remove();
            }
        }
        if (captured || respawnTimer > 0) {
            return; // no vulnerable player ship right now
        }
        // enemy bullets vs player ship(s)
        for (Iterator<double[]> bi = enemyBullets.iterator(); bi.hasNext(); ) {
            double[] b = bi.next();
            if (hitsPlayer(b[0], b[1])) {
                bi.remove();
                playerHit();
                return;
            }
        }
        // diving enemy bodies vs player
        for (Enemy e : enemies) {
            if (e.alive && (e.state == DIVING) && hitsPlayer(e.x, e.y)) {
                e.alive = false;
                score += scoreFor(e);
                playerHit();
                return;
            }
        }
    }

    private boolean hitsPlayer(double x, double y) {
        if (Math.abs(y - PLAYER_Y) >= 1.0) {
            return false;
        }
        if (dual) {
            return Math.abs(x - (playerX - DUAL_OFFSET)) < 1.0 || Math.abs(x - (playerX + DUAL_OFFSET)) < 1.0;
        }
        return Math.abs(x - playerX) < 1.0;
    }

    private void playerHit() {
        if (dual) {
            dual = false; // lose the second ship, keep flying single
            return;
        }
        lives--;
        if (lives <= 0) {
            gameOver = true;
        } else {
            playerX = FIELD_W / 2.0;
            respawnTimer = 1.0;
        }
    }

    // formation / waves

    private void spawnWave() {
        enemies.clear();
        beamActive = false;
        captured = false;
        captureBoss = null;
        diveTimer = 1.5;
        for (int r = 0; r < FORM_ROWS; r++) {
            int type = r == 0 ? BOSS : (r <= 2 ? BUTTERFLY : BEE);
            for (int col = 0; col < FORM_COLS; col++) {
                if (type == BOSS && (col < 2 || col > FORM_COLS - 3)) {
                    continue; // a shorter boss row (center four)
                }
                Enemy e = new Enemy();
                e.type = type;
                e.slotR = r;
                e.slotC = col;
                e.state = ENTERING;
                e.t = -(col + r) * 0.06; // stagger the entrance so they stream in
                boolean fromLeft = (r % 2 == 0);
                e.startX = fromLeft ? -3 : FIELD_W + 3;
                e.startY = 1 + r;
                enemies.add(e);
            }
        }
    }

    private void launchDive() {
        List<Enemy> pool = new ArrayList<>();
        for (Enemy e : enemies) {
            if (e.alive && e.state == FORMATION) {
                pool.add(e);
            }
        }
        if (pool.isEmpty()) {
            return;
        }
        Enemy e = pool.get(rng.nextInt(pool.size()));
        e.t = 0;
        e.startX = e.x;
        e.startY = e.y;
        e.targetX = clamp(playerX, 2, FIELD_W - 2);
        e.amp = 2.0 + rng.nextDouble() * 4.0;
        e.dir = rng.nextBoolean() ? 1 : -1;
        e.shootT = 0.4;
        boolean canCapture = e.type == BOSS && !e.holdsCaptive && !anyHeldCaptive()
                && captureBoss == null && lives > 0 && rng.nextDouble() < 0.35;
        e.state = canCapture ? BEAMING : DIVING;
    }

    private boolean anyHeldCaptive() {
        for (Enemy e : enemies) {
            if (e.alive && e.holdsCaptive) {
                return true;
            }
        }
        return false;
    }

    private boolean formationSettled() {
        for (Enemy e : enemies) {
            if (e.alive && e.state == ENTERING) {
                return false;
            }
        }
        return true;
    }

    private int aliveCount() {
        int n = 0;
        for (Enemy e : enemies) {
            if (e.alive) {
                n++;
            }
        }
        return n;
    }

    private int scoreFor(Enemy e) {
        boolean diving = e.state == DIVING || e.state == BEAMING || e.state == RETURNING;
        return switch (e.type) {
            case BOSS -> diving ? 400 : 150;
            case BUTTERFLY -> diving ? 160 : 80;
            default -> diving ? 100 : 50;
        };
    }

    /* The drawn position of the captive a boss is holding: held BESIDE the boss (not below), so a shot up the
       boss's column hits the BOSS (rescue -> dual fighter) while a shot up the captive's column hits the captive
       (destroys it, no rescue). If it were below, every upward shot would hit the captive first and rescue would be
       impossible. Public so the renderer's draw position matches the collision test exactly. */
    public double captiveX(Enemy boss) {
        return boss.x + 1.5;
    }

    public double captiveY(Enemy boss) {
        return boss.y;
    }

    private double slotX(Enemy e) {
        double center = FIELD_W / 2.0 + Math.sin(formSway) * SWAY_AMP;
        return center + (e.slotC - (FORM_COLS - 1) / 2.0) * FORM_SPACING_X;
    }

    private double slotY(Enemy e) {
        return FORM_TOP + e.slotR * FORM_SPACING_Y;
    }

    private void moveBullets(List<double[]> bullets, double dy) {
        for (Iterator<double[]> it = bullets.iterator(); it.hasNext(); ) {
            double[] b = it.next();
            b[1] += dy;
            if (b[1] < 0 || b[1] > FIELD_H) {
                it.remove();
            }
        }
    }

    private static boolean hit(double x1, double y1, double x2, double y2) {
        return Math.abs(x1 - x2) < 1.0 && Math.abs(y1 - y2) < 1.0;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static double clamp01(double v) {
        return clamp(v, 0, 1);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }
}
