package com.retro.bamfmenu.early.core.secret;

import java.util.Locale;

/* How a secret is entered. Two data-driven kinds, and a secret carries exactly one:
   - Phrase: a typed word/phrase, matched CASE-INSENSITIVELY when you press ENTER in the secret box.
   - Keys:   a Konami-style ordered run of GLFW key codes, matched LIVE as you press them (no ENTER).
   Sealed so the SecretRegistry handles each kind explicitly and the set can't grow by accident. Pure data -- no GL. */
public sealed interface Trigger permits Trigger.Phrase, Trigger.Keys {

    /* A typed phrase, stored normalized (trimmed + lowercased) so matching ignores case. */
    record Phrase(String text) implements Trigger {
        public Phrase {
            text = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        }

        /* True if the typed buffer equals this phrase (case-insensitive, trimmed). Empty phrase never matches. */
        public boolean matches(String typed) {
            return typed != null && !text.isEmpty() && typed.trim().toLowerCase(Locale.ROOT).equals(text);
        }
    }

    /* An ordered run of GLFW key codes (e.g. the arrow keys). Matched when the most-recent keys end with this run. */
    record Keys(int[] codes) implements Trigger {
        public Keys {
            codes = codes == null ? new int[0] : codes.clone();
        }

        public int length() {
            return codes.length;
        }

        /* True if `history` (valid length `n`, most-recent key last) ends with this exact code run. */
        public boolean endsWith(int[] history, int n) {
            int m = codes.length;
            if (m == 0 || n < m) {
                return false;
            }
            for (int i = 0; i < m; i++) {
                if (history[n - m + i] != codes[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    /* Factory: a typed-phrase trigger (case-insensitive). */
    static Trigger phrase(String text) {
        return new Phrase(text);
    }

    /* Factory: a key-sequence trigger from GLFW key codes (varargs, e.g. arrows). */
    static Trigger keys(int... codes) {
        return new Keys(codes);
    }
}
