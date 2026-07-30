import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;

// Pings a live server using the engine's own Slp and BetterStatus classes to verify real BCC broadcast output.
public class Broadcast {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: Broadcast <host> <port> [serverDir]");
            return;
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        Path engine = Paths.get(System.getProperty("bamfos.engine", "")).toAbsolutePath();
        URLClassLoader cl = new URLClassLoader(new URL[]{engine.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());

        Class<?> slp = Class.forName("com.retro.bamfos.updater.engine.ping.Slp", true, cl);
        Method status = slp.getMethod("status", String.class, int.class);
        String json;
        try {
            json = (String) status.invoke(null, host, port);
        } catch (Exception unreachable) {
            System.out.println("UNREACHABLE " + host + ":" + port + " -> "
                    + (unreachable.getCause() != null ? unreachable.getCause() : unreachable));
            System.out.println("VERDICT no carrier, which is what a client would see too");
            return;
        }
        System.out.println("RAW LENGTH " + json.length());
        int at = json.indexOf("\"betterStatus\"");
        System.out.println("HAS betterStatus " + (at >= 0));
        if (at >= 0) {
            System.out.println("SLICE " + json.substring(at, Math.min(json.length(), at + 90)));
        }

        Class<?> bs = Class.forName("com.retro.bamfos.updater.engine.ping.BetterStatus", true, cl);
        Object parsed = bs.getMethod("parse", String.class).invoke(null, json);
        Object val = parsed.getClass().getMethod("isPresent").invoke(parsed);
        if (!Boolean.TRUE.equals(val)) {
            System.out.println("PARSED none, so a client reads this as NO SIGNAL and does not update");
            return;
        }
        Object rec = parsed.getClass().getMethod("get").invoke(parsed);
        String name = (String) rec.getClass().getMethod("name").invoke(rec);
        String version = (String) rec.getClass().getMethod("version").invoke(rec);
        System.out.println("PARSED name=" + name + " version=" + version);

        if (args.length >= 3) {
            Class<?> doors = Class.forName("com.retro.bamfos.updater.engine.door.Doors", true, cl);
            Object settled = doors.getMethod("settledVersion", Path.class)
                    .invoke(null, Paths.get(args[2]).toAbsolutePath());
            Object has = settled.getClass().getMethod("isPresent").invoke(settled);
            String s = Boolean.TRUE.equals(has)
                    ? (String) settled.getClass().getMethod("get").invoke(settled) : "(none)";
            System.out.println("SETTLED " + s);
            System.out.println("VERDICT " + (version.equals(s)
                    ? "agree, so every client converges to what this box really is"
                    : "DISAGREE, the broadcast is " + version + " and the box settled on " + s));
        }
    }
}
