package com.retro.bamfos.updater.engine.reconcile;

import com.retro.bamfos.updater.engine.reconcile.SyncPlan.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies user-facing reason wording: the same reconciler Kind means different things in sync vs repair. */
class ReasonsTest {

    @Test
    void aReplaceIsANewVersionWhenSyncingAndAMismatchWhenRepairing() {
        assertEquals("newer version", Reasons.of(Kind.REPLACE, false));
        assertEquals("sha mismatch", Reasons.of(Kind.REPLACE, true));
    }

    @Test
    void nothingOnDiskReadsAsNewWhenSyncingAndMissingWhenRepairing() {
        assertEquals("new file", Reasons.of(Kind.INSTALL, false));
        assertEquals("missing", Reasons.of(Kind.INSTALL, true));
        assertEquals("missing", Reasons.of(Kind.RESTORE, false));
        assertEquals("missing", Reasons.of(Kind.RESTORE, true));
    }

    /** Overwriting a user-edited file uses the same reason text regardless of which op is running. */
    @Test
    void anOverwrittenEditAlwaysNamesTheBackup() {
        assertEquals("edited, backed up", Reasons.of(Kind.REPLACE_BAK, false));
        assertEquals("edited, backed up", Reasons.of(Kind.REPLACE_BAK, true));
    }

    @Test
    void everyKindHasWordsAndNoneIsASentence() {
        assertFalse(Reasons.SELF.isBlank());
        assertTrue(Reasons.SELF.length() <= 20, String.valueOf(Reasons.SELF));
        for (Kind k : Kind.values()) {
            for (boolean repair : new boolean[]{false, true}) {
                String s = Reasons.of(k, repair);
                if (k == Kind.ADOPT) {
                    assertEquals("", s);
                    continue;
                }
                assertFalse(s.isBlank(), k + " has no words");
                // Shown in a narrow table cell next to a filename, so keep it short so it doesn't wrap badly.
                assertTrue(s.length() <= 20, k + " is too long for the cell: " + s);
                assertFalse(s.endsWith("."), k + " is written as a sentence: " + s);
            }
        }
    }

    /** Null kind shouldn't crash since this runs during UI rendering. */
    @Test
    void aNullKindIsBlankNotAThrow() {
        assertEquals("", Reasons.of(null, false));
    }
}
