import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;

// Runs a BIOS repair op headlessly against a real install through OpsGateway (the same isolated classloader path OpsBridge uses).
public class Bios {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: Bios <op>   (status | repair | syncNow | listOptional | ...)");
            return;
        }
        Path dir = Paths.get(".").toAbsolutePath().normalize();
        Path jar = dir.resolve(".bamfos-updater/engine/bamfos-updater-engine.jar");
        URLClassLoader cl = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
        Class<?> gw = Class.forName("com.retro.bamfos.updater.engine.ops.OpsGateway", true, cl);
        gw.getMethod("bind", Path.class, String.class).invoke(null, dir, "https://api.github.com");
        Method invoke = gw.getMethod("invoke", String.class, Object[].class);
        System.out.println("OP " + args[0] + " -> " + invoke.invoke(null, args[0], new Object[0]));
    }
}
