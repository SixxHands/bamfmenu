package com.retro.bamfos.updater.boot;

import java.util.function.BiFunction;

/**
 * The shim's one same-floor handle (service floor may share classes; everything else
 * rides the file bus). The earlywindow's BIOS reaches the engine's UpdaterOps through here,
 * Class.forName this, call ops(...). Progress display does NOT come through here on purpose: it
 * tails .bamfos-updater/bus.log, which survives every classloader topology this can't.
 */
public final class UpdaterBridge {

    private static volatile BiFunction<String, Object[], Object> ops;

    /** Bound by ClientLocator after the engine loads; absent engine or old engine = never bound. */
    static void bindOps(BiFunction<String, Object[], Object> invoker) {
        ops = invoker;
    }

    /** True once the updater ran this boot and its ops surface is callable. */
    public static boolean present() {
        return ops != null;
    }

    /* Calls an engine op by name using JDK types only ("error: ..." strings instead of throwing).
     * Returns null when no updater is bound so the BIOS hides the updater UI and keeps SETUP row 0 as the joke it always was. */
    public static Object ops(String op, Object[] args) {
        BiFunction<String, Object[], Object> o = ops;
        return o == null ? null : o.apply(op, args);
    }

    private UpdaterBridge() {
    }
}
