package com.retro.bamfos.updater.boot;

import java.util.function.BiFunction;

/* Service-layer bridge so earlywindow's BIOS can call engine ops via reflection (progress bars tail
 * bus.log instead). Do NOT move or rename this class, OpsBridge looks it up by literal string. */
public final class UpdaterBridge {

    private static volatile BiFunction<String, Object[], Object> ops;

    /** Bound by CoreSlot from the returned ops handle; stays unbound if no core or an older version ran. */
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
