package com.retro.bamfmenu.early.core.updater;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Reflective bridge to UpdaterBridge (no compile-time dependency on the updater). Returns empty/null cleanly if the updater isn't installed or is older.
public final class OpsBridge {

    /** Reflective handle to the updater shim. Missing just means no updater is installed. */
    private static final String BRIDGE_CLASS = "com.retro.bamfos.updater.boot.UpdaterBridge";
    /** Sentinel when an op doesn't return an int (older engines or errors return an "error: ..." string). */
    public static final int NO_ANSWER = -1;

    private static final Method PRESENT;
    private static final Method OPS;
    private static volatile boolean latched; // Once the shim is bound, we stay marked as present.

    static {
        Method present = null;
        Method ops = null;
        try {
            Class<?> bridge = Class.forName(BRIDGE_CLASS);
            present = bridge.getMethod("present");
            ops = bridge.getMethod("ops", String.class, Object[].class);
        } catch (Throwable noUpdater) {
            // No shim jar, old shim, or inaccessible module: keep the default BIOS row.
        }
        PRESENT = present;
        OPS = ops;
    }

    private OpsBridge() {
    }

    /** True once the updater ran this boot and its ops are callable. */
    public static boolean present() {
        if (latched) {
            return true;
        }
        if (PRESENT == null) {
            return false;
        }
        try {
            if (Boolean.TRUE.equals(PRESENT.invoke(null))) {
                latched = true;
                return true;
            }
        } catch (Throwable t) {
            return false; // a reflective hiccup is an absent updater, never a broken terminal
        }
        return false;
    }

    /** Returns the int result of an op, or NO_ANSWER if the engine returned something else. */
    public static int code(String op, Object... args) {
        Object answer = call(op, args);
        return answer instanceof Integer i ? i : NO_ANSWER;
    }

    /** Returns the boolean result of an op, or false if the engine returned something else. */
    public static boolean flag(String op, Object... args) {
        return Boolean.TRUE.equals(call(op, args));
    }

    /** Returns the string result of an op, or "" if the engine returned something else. */
    public static String text(String op, Object... args) {
        Object answer = call(op, args);
        return answer instanceof String s && !s.startsWith("error: ") ? s : "";
    }

    /** Returns the map result of an op with String keys, or empty if the engine returned something else. */
    public static Map<String, Object> map(String op, Object... args) {
        return asMap(call(op, args));
    }

    /** Returns the list-of-maps result of an op, or empty if the engine returned something else. */
    public static List<Map<String, Object>> rows(String op, Object... args) {
        Object answer = call(op, args);
        List<Map<String, Object>> out = new ArrayList<>();
        if (answer instanceof List<?> list) {
            for (Object row : list) {
                if (row instanceof Map<?, ?>) {
                    out.add(asMap(row));
                }
            }
        }
        return out;
    }

    /** Extracts a nested list of maps from a map result (used by listReleases). */
    public static List<Map<String, Object>> rowsIn(Map<String, Object> answer, String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (answer.get(key) instanceof List<?> list) {
            for (Object row : list) {
                if (row instanceof Map<?, ?>) {
                    out.add(asMap(row));
                }
            }
        }
        return out;
    }

    /* Single reflective call site. Ops can do HTTP and file I/O, so callers must run this
       on a worker thread, never on the render thread. */
    private static Object call(String op, Object... args) {
        if (OPS == null) {
            return null;
        }
        try {
            return OPS.invoke(null, op, args == null ? new Object[0] : args);
        } catch (Throwable t) {
            System.err.println("[bamfmenu] updater op '" + op + "' failed: " + t);
            return null;
        }
    }

    /* A foreign Map copied key-by-key into our own. The engine promises String keys, and we still ask
       each one, because an unchecked cast here would throw INSIDE a render frame at the first surprise. */
    private static Map<String, Object> asMap(Object answer) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (answer instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() instanceof String k) {
                    out.put(k, e.getValue());
                }
            }
        }
        return out;
    }
}
