import com.retro.bamfmenu.early.bios.host.TerminalWindowProvider;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/* Dev-only helper driven by tools/shoot.py. Launches the real early window outside Minecraft,
 * replays a bus.log + key sequence, and saves a PNG screenshot.
 * Usage: BootShot <outDir> <gameDir> <name> <busFile|-> <keys|-> */
public final class BootShot {

    public static void main(String[] args) throws Exception {
        String outDir = args[0];
        String gameDir = args[1];
        String name = args[2];
        String busFile = args[3];
        String keys = args[4];
        new File(outDir).mkdirs();

        // write bus entries after initialize() since UpdaterFeed truncates the file in its constructor
        Path bus = Path.of(gameDir, ".bamfos-updater", "bus.log");
        Files.createDirectories(bus.getParent());
        Files.createDirectories(Path.of(gameDir, "mods"));
        Files.writeString(Path.of(gameDir, "mods", "bamfos-updater-boot.jar"), "shim");
        String busBody = "-".equals(busFile) ? "" : Files.readString(Path.of(busFile), StandardCharsets.UTF_8);
        // poll() buffers an unterminated final line waiting for more writes, so make sure posed files end with \n or the last event gets dropped.
        if (!busBody.isEmpty() && !busBody.endsWith("\n")) {
            busBody = busBody + "\n";
        }

        if (args.length > 5 && "bound".equals(args[5])) {
            bindUpdater();
        }
        TerminalWindowProvider p = new TerminalWindowProvider();
        Runnable tick = p.initialize(new String[]{
                "--width", "1600", "--height", "900",
                "--fml.mcVersion", "1.21.1", "--fml.neoForgeVersion", "21.1.219",
                "--gameDir", gameDir});
        if (!busBody.isEmpty()) {
            Files.writeString(bus, busBody, StandardCharsets.UTF_8);
        }
        waitForRender(p);

        long win = handle(p);
        pump(tick, 200); // POST counts memory up and flips devices to OK on a frame counter, so let it settle
        if (!"-".equals(keys)) {
            for (String k : keys.split(",")) {
                send(win, k.trim());
                pump(tick, 45);
            }
        }
        pump(tick, 30);
        dumpPostBlock(p); // says whether a missing row was never built or was built and not drawn
        grab(p, win, new File(outDir, name + ".png"));
        System.exit(0); // GLFW keeps threads of its own alive
    }

    // Invoke the provider's installed GLFW key callback directly (window is hidden so OS focus/Robot won't deliver keys).
    private static void send(long win, String key) {
        int code = switch (key) {
            case "DEL" -> org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE;
            case "ENTER" -> org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
            case "DOWN" -> org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
            case "UP" -> org.lwjgl.glfw.GLFW.GLFW_KEY_UP;
            case "ESC" -> org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
            default -> throw new IllegalArgumentException("unknown key " + key);
        };
        org.lwjgl.glfw.GLFWKeyCallback cb = org.lwjgl.glfw.GLFW.glfwSetKeyCallback(win, null);
        if (cb == null) {
            throw new IllegalStateException("the provider installed no key callback");
        }
        org.lwjgl.glfw.GLFW.glfwSetKeyCallback(win, cb); // put it straight back, we only wanted the handle
        cb.invoke(win, code, 0, org.lwjgl.glfw.GLFW.GLFW_PRESS, 0);
        cb.invoke(win, code, 0, org.lwjgl.glfw.GLFW.GLFW_RELEASE, 0);
    }

    // Bind a dummy UpdaterBridge so SETUP row 0 renders as Update and Repair instead of the no-updater joke.
    private static void bindUpdater() throws Exception {
        Class<?> bridge = Class.forName("com.retro.bamfos.updater.boot.UpdaterBridge");
        java.lang.reflect.Method bind = bridge.getDeclaredMethod("bindOps", java.util.function.BiFunction.class);
        bind.setAccessible(true); // package-private on purpose, only the shim is meant to call it
        bind.invoke(null, (java.util.function.BiFunction<String, Object[], Object>) (op, a) -> null);
    }

    private static void pump(Runnable tick, int frames) throws InterruptedException {
        for (int i = 0; i < frames; i++) {
            tick.run();
            Thread.sleep(16);
        }
    }

    /** initRender rides a Future nobody inspects, so a throw in it is invisible except as a blank window. */
    private static void waitForRender(TerminalWindowProvider p) throws Exception {
        Field f = TerminalWindowProvider.class.getDeclaredField("initFuture");
        f.setAccessible(true);
        ((Future<?>) f.get(p)).get(30, TimeUnit.SECONDS);
        Field ff = TerminalWindowProvider.class.getDeclaredField("initFailed");
        ff.setAccessible(true);
        if (Boolean.TRUE.equals(ff.get(p))) {
            throw new IllegalStateException("the provider's GL init failed, see its stderr above");
        }
    }

    // Prints the feed's built rows to stdout so we can tell if a missing line wasn't parsed vs wasn't drawn.
    private static void dumpPostBlock(TerminalWindowProvider p) throws Exception {
        Field f = TerminalWindowProvider.class.getDeclaredField("updaterFeed");
        f.setAccessible(true);
        Object feed = f.get(p);
        if (feed == null) {
            System.out.println("  POSTBLOCK: no feed");
            return;
        }
        Path busLog = Path.of(System.getProperty("bootshot.gamedir", "."), ".bamfos-updater", "bus.log");
        if (Files.isRegularFile(busLog)) {
            String body = Files.readString(busLog, StandardCharsets.UTF_8);
            System.out.println("  BUS " + Files.size(busLog) + " bytes, " + body.lines().count() + " lines");
            body.lines().forEach(l -> System.out.println("  BUS | " + l.replace("\t", "<TAB>")));
        } else {
            System.out.println("  BUS MISSING at " + busLog);
        }
        Object rows = feed.getClass().getMethod("postBlock", int.class).invoke(feed, 6);
        for (Object row : (java.util.List<?>) rows) {
            Object text = row.getClass().getMethod("text").invoke(row);
            Object fill = row.getClass().getMethod("fill").invoke(row);
            System.out.println("  POSTBLOCK fill=" + fill + " | " + text);
        }
    }

    /** Reflection rather than a getter: test-only surface does not belong on a shipped class. */
    private static long handle(TerminalWindowProvider p) throws Exception {
        Field f = TerminalWindowProvider.class.getDeclaredField("window");
        f.setAccessible(true);
        long win = (Long) f.get(p);
        if (win == 0L) {
            throw new IllegalStateException("the provider never created a window");
        }
        return win;
    }

    // Reads GL_FRONT directly on the render thread while the GL context is still current (GL_BACK is empty after swap).
    private static void grab(TerminalWindowProvider p, long win, File out) throws Exception {
        Field sf = TerminalWindowProvider.class.getDeclaredField("renderScheduler");
        sf.setAccessible(true);
        ScheduledExecutorService sched = (ScheduledExecutorService) sf.get(p);

        int[] fw = new int[1];
        int[] fh = new int[1];
        org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(win, fw, fh);
        final int w = fw[0];
        final int h = fh[0];

        java.nio.ByteBuffer buf = sched.submit(() -> {
            org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(win);
            org.lwjgl.opengl.GL.createCapabilities();
            java.nio.ByteBuffer b = org.lwjgl.BufferUtils.createByteBuffer(w * h * 4);
            org.lwjgl.opengl.GL11.glReadBuffer(org.lwjgl.opengl.GL11.GL_FRONT);
            org.lwjgl.opengl.GL11.glReadPixels(0, 0, w, h, org.lwjgl.opengl.GL11.GL_RGBA,
                    org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, b);
            return b;
        }).get(30, TimeUnit.SECONDS);

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = (x + (h - 1 - y) * w) * 4; // GL's origin is bottom-left, an image's is top-left
                img.setRGB(x, y, ((buf.get(i) & 0xFF) << 16)
                        | ((buf.get(i + 1) & 0xFF) << 8) | (buf.get(i + 2) & 0xFF));
            }
        }
        ImageIO.write(img, "png", out);
        System.out.println("  " + out.getName() + "  " + w + "x" + h);
    }

    private BootShot() {
    }
}
