package com.retro.bamfos.updater.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Test fixture zipped into core-NNNN.jar using the real CoreEntry class name for stub handoff tests.
 * Behavior is configured per jar via a system property keyed by filename; the platform-parented
 * classloader always loads the copy inside the jar instead of this test-classpath class.
 */
public final class CoreEntry {

    /** Property prefix plus the jar filename (e.g. bamfos.test.core.core-0009.jar) to configure each jar. */
    public static final String BEHAVIOUR = "bamfos.test.core.";

    /** Log file in the target dir recording one line per generation that reached enter(). */
    public static final String RAN_LOG = "cores-that-ran.txt";

    static {
        if ("wont-init".equals(behaviour())) {
            // Simulates a core with a broken static initializer or incompatible Java version.
            throw new IllegalStateException("this generation cannot initialise");
        }
    }

    public static Object enter(String[] args) {
        noteThatWeRan(args);
        String how = behaviour();
        if ("throws".equals(how)) {
            throw new IllegalStateException("ran, then fell over");
        }
        if ("null".equals(how)) {
            return null;
        }
        if ("not-a-map".equals(how)) {
            return "a core from the future answering with something else";
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("exit", 7);
        answer.put("sawTheStub", sawTheStub());
        if ("ops".equals(how)) {
            answer.put("ops", (BiFunction<String, Object[], Object>) (op, callArgs) -> "ok:" + op);
        }
        if ("bad-ops".equals(how)) {
            answer.put("ops", "not a function at all");
        }
        return answer;
    }

    /** Returns the jar filename this class was loaded from. */
    static String jarName() {
        try {
            return Paths.get(CoreEntry.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .getFileName().toString();
        } catch (Exception unknown) {
            return "";
        }
    }

    private static String behaviour() {
        return System.getProperty(BEHAVIOUR + jarName(), "");
    }

    private static void noteThatWeRan(String[] args) {
        Path dir = Paths.get(".");
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--dir")) {
                dir = Paths.get(args[i + 1]);
            }
        }
        try {
            Files.writeString(dir.resolve(RAN_LOG), jarName() + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException unwritable) {
        }
    }

    /** Returns true if the parent classloader isn't the platform loader (meaning isolation broke). */
    private static boolean sawTheStub() {
        try {
            Class.forName("com.retro.bamfos.updater.boot.UpdaterBridge");
            return true;
        } catch (Throwable isolated) {
            return false;
        }
    }

    private CoreEntry() {
    }
}
