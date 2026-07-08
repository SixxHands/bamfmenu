package com.retro.bamfmenu.early.core.secret;

/* One data-driven secret: a `name` (for logs), a `Trigger` (how it's entered), and a `SecretAction` (what it does).
   Declare one with `new Secret("name", Trigger.phrase("word"), ctx -> ...)` -- or the phrase() factory -- inside a
   SecretDef class under secret/defs/, and the SecretRegistry auto-discovers it (no central list). The action does
   WHATEVER through the SecretContext seam (toggle a mode, show a notice, launch a hidden song, ...). Immutable. */
public record Secret(String name, Trigger trigger, SecretAction action) {

    /* Convenience: a typed-phrase secret (case-insensitive). */
    public static Secret phrase(String name, String phrase, SecretAction action) {
        return new Secret(name, Trigger.phrase(phrase), action);
    }

    /* Convenience: a key-sequence secret (GLFW key codes, e.g. arrows), matched live as you press them. */
    public static Secret keys(String name, int[] codes, SecretAction action) {
        return new Secret(name, Trigger.keys(codes), action);
    }
}
