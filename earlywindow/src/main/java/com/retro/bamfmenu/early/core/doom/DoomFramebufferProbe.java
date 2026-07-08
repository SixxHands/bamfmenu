package com.retro.bamfmenu.early.core.doom;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/* DEV-ONLY (run via `gradlew :earlywindow:genDoomFrame`, java.awt.headless=true): proves the Phase-2 renderer core --
   DOOM boots headless through DoomEngine (no window), DoomAscii downsamples a live frame to COLORED ASCII (dumps the
   glyphs + counts distinct colours), and start()/stop()/start() is restart-safe (a fresh engine becomes ready again,
   not the stale one). Never referenced by the mod; ships as harmless dead code (like SongSpike). */
public final class DoomFramebufferProbe {

    public static void main(String[] args) throws Exception {
        DoomEngine eng = new DoomEngine();
        long t0 = System.currentTimeMillis();
        eng.start();
        boolean ready = await(eng, 20000);
        System.out.println("[probe] ready=" + ready + " failed=" + eng.failed() + " in "
                + (System.currentTimeMillis() - t0) + "ms");
        if (!ready) {
            System.out.println("==== PHASE 2A FAIL: engine never became ready ====");
            System.exit(1);
        }

        Thread.sleep(800);
        long start = System.currentTimeMillis();
        int samples = 0, distinct = 0;
        long last = Long.MIN_VALUE;
        DoomEngine.Frame lastFrame = null;
        while (System.currentTimeMillis() - start < 8000) {
            DoomEngine.Frame f = eng.frame();
            if (f != null) {
                long sum = checksum(f);
                if (sum != last) {
                    distinct++;
                    last = sum;
                    lastFrame = f;
                }
                samples++;
            }
            Thread.sleep(8);
        }
        System.out.printf("[probe] %d samples, %d distinct frames over 8s%n", samples, distinct);

        int distinctColors = 0;
        if (lastFrame != null) {
            DoomAscii.Rendered r = DoomAscii.render(lastFrame, 100, 56);
            Set<Integer> palette = new HashSet<>();
            boolean allOpaque = true;
            for (int c : r.colors()) {
                palette.add(c);
                if ((c & 0xFF000000) != 0xFF000000) {
                    allOpaque = false;
                }
            }
            distinctColors = palette.size();
            System.out.println("[probe] DoomAscii grid " + r.cols() + "x" + r.rows() + ", distinct cell colours="
                    + distinctColors + ", all cells opaque ABGR=" + allOpaque);
            if (!allOpaque) {
                System.out.println("[probe] FAIL: cells must be opaque (alpha=0xFF) ABGR or they render transparent");
                System.exit(1);
            }
            StringBuilder sb = new StringBuilder();
            for (int y = 0; y < r.rows(); y++) {
                for (int x = 0; x < r.cols(); x++) {
                    sb.append(r.glyphs()[y * r.cols() + x]);
                }
                sb.append('\n');
            }
            Files.writeString(Path.of("doom-frame.txt"), sb.toString());
            System.out.println("[probe] dumped doom-frame.txt; preview (every other row):");
            String[] rows = sb.toString().split("\n");
            for (int i = 0; i < rows.length; i += 2) {
                System.out.println(rows[i]);
            }
        }

        eng.stop();
        Thread.sleep(300);
        boolean stoppedClean = !eng.isRunning();
        System.out.println("[probe] after stop: running=" + eng.isRunning());

        // restart cleanliness -- a fresh engine must resolve (not the stale instance)
        eng.start();
        boolean restartReady = await(eng, 20000);
        // HUD/menu reflection check: open DOOM's menu (Tab -> Esc) and read the live
        // items via DoomState
        Thread.sleep(500);
        eng.postGlfwKey(258, true);
        eng.postGlfwKey(258, false);
        Thread.sleep(800);
        DoomState.Snapshot mst = eng.hud();
        System.out.println("[probe] menu: active=" + mst.menuActive() + " sel=" + mst.menuSel()
                + " items=" + (mst.menuItems() == null ? "null" : java.util.Arrays.toString(mst.menuItems())));
        System.out.println("[probe] figlet fonts loaded: Poison=" + (Figlet.font("Poison") != null)
                + " Doom=" + (Figlet.font("Doom") != null) + " small=" + (Figlet.font("small") != null));
        // drive New Game -> skill -> into E1M1 to verify the in-level HUD reflection
        // (health/armor/ammo/weapon)
        for (int k = 0; k < 3; k++) {
            eng.postGlfwKey(257, true);
            eng.postGlfwKey(257, false);
            Thread.sleep(800);
        }
        Thread.sleep(1200);
        DoomState.Snapshot gst = eng.hud();
        System.out.println("[probe] in-game: inLevel=" + gst.inLevel() + " HP=" + gst.health()
                + " ARM=" + gst.armor() + " AMMO=" + gst.ammo() + "(" + gst.hasAmmo() + ") WPN=" + gst.weapon());
        System.out.println("[probe] HUD arrays: arms=" + java.util.Arrays.toString(gst.arms())
                + " ammo=" + java.util.Arrays.toString(gst.ammoCounts())
                + " maxammo=" + java.util.Arrays.toString(gst.ammoMax())
                + " keys=" + java.util.Arrays.toString(gst.keys()));
        eng.stop();
        System.out.println("[probe] restart ready=" + restartReady);

        boolean pass = distinct > 0 && lastFrame != null && distinctColors > 8 && stoppedClean && restartReady;
        System.out.println(pass ? "==== PHASE 2A PASS: headless DOOM -> colored ASCII + clean restart ===="
                : "==== PHASE 2A FAIL ====");
        System.exit(pass ? 0 : 1);
    }

    static boolean await(DoomEngine eng, long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            if (eng.ready()) {
                return true;
            }
            if (eng.failed()) {
                return false;
            }
            Thread.sleep(30);
        }
        return eng.ready();
    }

    static long checksum(DoomEngine.Frame f) {
        byte[] b = f.indices();
        int n = b.length;
        long s = 0;
        for (int i = 0; i < 96; i++) {
            s = s * 31 + b[(int) (((long) i * 49999) % n)];
        }
        return s;
    }
}
