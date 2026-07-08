import java.awt.Image;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/* DOOM spike harness: boot Mocha Doom on a daemon thread, reflect its framebuffer (getScreenImage), measure how many
   DISTINCT frames/sec it produces (proxy for the engine's render rate in plain Java), and dump one frame downsampled
   to ASCII so we can confirm it's REAL DOOM imagery, the exact path we'd use in the early-window terminal.
   Args: <wad-path> */
public final class VerifyDoomSpike {
    static final char[] RAMP = " .:-=+*#%@".toCharArray(); // dark -> bright luminance ramp

    public static void main(String[] argv) throws Exception {
        final String wad = argv[0];
        Class<?> engineCls = Class.forName("mochadoom.Engine");
        Method engineMain = engineCls.getMethod("main", String[].class);

        Thread doom = new Thread(() -> {
            try { engineMain.invoke(null, (Object) new String[]{"-nosound", "-iwad", wad, "-playdemo", "demo1"}); }
            catch (Throwable e) { System.out.println("DOOM thread threw: " + e); }
        }, "doom-loop");
        doom.setDaemon(true);
        doom.start();

        // Wait for the engine instance + its graphicSystem (reflect the private static `instance` + private `DOOM`).
        Field fInstance = engineCls.getDeclaredField("instance"); fInstance.setAccessible(true);
        Object engine = null, gs = null;
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 20000) {
            Object inst = fInstance.get(null);
            if (inst != null) {
                Field fDoom = engineCls.getDeclaredField("DOOM"); fDoom.setAccessible(true);
                Object doomMain = fDoom.get(inst);
                if (doomMain != null) {
                    Field fGs = doomMain.getClass().getField("graphicSystem"); // public
                    Object g = fGs.get(doomMain);
                    if (g != null) { engine = inst; gs = g; break; }
                }
            }
            Thread.sleep(50);
        }
        if (gs == null) { System.out.println("[FAIL] never got graphicSystem"); System.exit(1); }
        System.out.println("[OK] got graphicSystem: " + gs.getClass().getName());

        Method mImg = gs.getClass().getMethod("getScreenImage"); mImg.setAccessible(true);
        Method mW = gs.getClass().getMethod("getScreenWidth"); mW.setAccessible(true);
        Method mH = gs.getClass().getMethod("getScreenHeight"); mH.setAccessible(true);
        int w = (int) mW.invoke(gs), h = (int) mH.invoke(gs);
        System.out.println("[OK] DOOM screen size: " + w + "x" + h);

        // Let it settle a moment so the title/demo is actually rendering.
        Thread.sleep(800);

        // Sample for 5s; count distinct frames via a cheap checksum.
        long sampleMs = 5000, start = System.currentTimeMillis();
        int samples = 0, distinct = 0; long lastSum = Long.MIN_VALUE;
        BufferedImage last = null;
        while (System.currentTimeMillis() - start < sampleMs) {
            BufferedImage bi = toBuffered((Image) mImg.invoke(gs), w, h);
            if (bi != null) {
                long sum = checksum(bi);
                if (sum != lastSum) { distinct++; lastSum = sum; last = bi; }
                samples++;
            }
            Thread.sleep(8); // ~125Hz sampling
        }
        double secs = (System.currentTimeMillis() - start) / 1000.0;
        System.out.printf("[OK] sampled %d times in %.1fs; %d DISTINCT frames -> ~%.1f fps engine render%n",
                samples, secs, distinct, distinct / secs);

        if (last != null) {
            String ascii = toAscii(last, 96, 54);
            java.nio.file.Files.writeString(java.nio.file.Path.of("spike-frame.txt"), ascii);
            // also print a small preview + a color check
            System.out.println("[OK] dumped ASCII frame to spike-frame.txt (96x54). Preview (every other row):");
            String[] rows = ascii.split("\n");
            for (int i = 0; i < rows.length; i += 2) System.out.println(rows[i]);
            System.out.println("[OK] color-varies check: " + colorVaries(last));
        }
        System.out.println(distinct > 0 ? "==== SPIKE PASS: live DOOM framebuffer captured ====" : "==== SPIKE FAIL: no frames ====");
        System.exit(distinct > 0 ? 0 : 1);
    }

    static BufferedImage toBuffered(Image img, int w, int h) {
        if (img == null) return null;
        if (img instanceof BufferedImage) return (BufferedImage) img;
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics g = bi.getGraphics(); g.drawImage(img, 0, 0, null); g.dispose();
        return bi;
    }

    static long checksum(BufferedImage bi) {
        long s = 0; int w = bi.getWidth(), h = bi.getHeight();
        for (int i = 0; i < 64; i++) {
            int x = (i * 37) % w, y = (i * 53) % h;
            s = s * 31 + bi.getRGB(x, y);
        }
        return s;
    }

    static boolean colorVaries(BufferedImage bi) {
        int w = bi.getWidth(), h = bi.getHeight();
        int first = bi.getRGB(w / 2, h / 2) & 0xffffff;
        for (int i = 0; i < 200; i++) {
            int c = bi.getRGB((i * 41) % w, (i * 67) % h) & 0xffffff;
            if (c != first) return true;
        }
        return false;
    }

    static String toAscii(BufferedImage bi, int cols, int rows) {
        int w = bi.getWidth(), h = bi.getHeight();
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int px = c * w / cols, py = r * h / rows;
                int rgb = bi.getRGB(px, py);
                int lum = (((rgb >> 16) & 0xff) * 30 + ((rgb >> 8) & 0xff) * 59 + (rgb & 0xff) * 11) / 100;
                sb.append(RAMP[Math.min(RAMP.length - 1, lum * RAMP.length / 256)]);
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
