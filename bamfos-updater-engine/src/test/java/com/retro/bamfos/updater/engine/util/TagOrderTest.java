package com.retro.bamfos.updater.engine.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies version-direction classification so rollbacks are never announced as updates. */
class TagOrderTest {

    /** Moving from v0.0.14-test to v0.0.3-test must classify as a rollback, not an update. */
    @Test
    void theMeasuredDowngradeIsNamedARollback() {
        assertEquals(TagOrder.Move.BACKWARD, TagOrder.of("v0.0.14-test", "v0.0.3-test"));
        assertEquals("rollback", TagOrder.noun("v0.0.14-test", "v0.0.3-test"));
        assertEquals("rolled back", TagOrder.pastVerb("v0.0.14-test", "v0.0.3-test"));
    }

    @Test
    void theOrdinaryForwardMoveStillReadsAsAnUpdate() {
        assertEquals(TagOrder.Move.FORWARD, TagOrder.of("v0.2.5", "v0.2.6"));
        assertEquals("update", TagOrder.noun("v0.2.5", "v0.2.6"));
        assertEquals("updated", TagOrder.pastVerb("v0.2.5", "v0.2.6"));
    }

    /**
     * Compares version components numerically rather than lexically, so 9 -> 10 is forward and 10 -> 9 is backward.
     */
    @Test
    void tenIsNewerThanNineEvenThoughItSortsFirstAsText() {
        assertEquals(TagOrder.Move.FORWARD, TagOrder.of("v0.2.9", "v0.2.10"));
        assertEquals(TagOrder.Move.BACKWARD, TagOrder.of("v0.2.10", "v0.2.9"));
    }

    /** `?` (first install with no ledger version) has no known direction and renders as an update. */
    @Test
    void anUnknownFromVersionClaimsNoDirectionAndKeepsTheOrdinaryWord() {
        assertEquals(TagOrder.Move.SIDEWAYS, TagOrder.of("?", "v0.2.6"));
        assertEquals("update", TagOrder.noun("?", "v0.2.6"));
        assertEquals("updated", TagOrder.pastVerb("?", "v0.2.6"));
    }

    /** Unrecognized tags return SIDEWAYS instead of guessing forward or backward. */
    @Test
    void anUnreadableTagClaimsNoDirection() {
        assertEquals(TagOrder.Move.SIDEWAYS, TagOrder.of("v0.2.6", "nightly"));
        assertEquals(TagOrder.Move.SIDEWAYS, TagOrder.of("v0.2.6", "v0.2.x"));
        assertEquals(TagOrder.Move.SIDEWAYS, TagOrder.of(null, "v0.2.6"));
        assertEquals(TagOrder.Move.SIDEWAYS, TagOrder.of("v0.2.6", null));
    }

    /** Date tags like v20260809 parse as major version 20260809 and compare newer than v0.2.6. */
    @Test
    void aDateStyleTagOutranksASemverOneRatherThanConfusingIt() {
        assertEquals(TagOrder.Move.FORWARD, TagOrder.of("v0.2.6", "v20260809-abcdef"));
        assertEquals(TagOrder.Move.BACKWARD, TagOrder.of("v20260809-abcdef", "v0.2.6"));
    }

    /** Pins that ONLY BACKWARD gets distinct rollback wording, keeping first-install text as update wording. */
    @Test
    void onlyAProvenBackwardMoveGetsItsOwnWord() {
        assertEquals("update", TagOrder.noun("nightly", "v0.2.6"));
        assertEquals("update", TagOrder.noun("v0.2.5", "v0.2.6"));
        assertEquals("update", TagOrder.noun("v0.2.6", "v0.2.6"));
        assertEquals("rollback", TagOrder.noun("v0.2.6", "v0.2.5"));
    }

    /** Two spellings of one version, and a missing trailing zero, are both the same version. */
    @Test
    void theSameVersionSpelledTwoWaysIsNotAMove() {
        assertEquals(TagOrder.Move.SAME, TagOrder.of("v0.2.6", "0.2.6"));
        assertEquals(TagOrder.Move.SAME, TagOrder.of("v0.2", "v0.2.0"));
        assertEquals(TagOrder.Move.SAME, TagOrder.of("v0.0.3-test", "v0.0.3-test"));
    }

    /** Leaving a -test prerelease for the matching release is forward and going back is backward, per semver. */
    @Test
    void droppingAPrereleaseSuffixIsForwardAndTakingOneOnIsBackward() {
        assertEquals(TagOrder.Move.FORWARD, TagOrder.of("v0.2.6-test", "v0.2.6"));
        assertEquals(TagOrder.Move.BACKWARD, TagOrder.of("v0.2.6", "v0.2.6-test"));
    }

    /** Comparing -test and -rc on the same version numbers is ambiguous, so treat them as unordered. */
    @Test
    void twoDifferentSuffixesOnOneVersionClaimNoDirection() {
        assertEquals(TagOrder.Move.SIDEWAYS, TagOrder.of("v0.2.6-test", "v0.2.6-rc1"));
    }

    /** A major or minor bump outranks any patch number under it, so 0.3.0 beats 0.2.99. */
    @Test
    void aMinorBumpOutranksALargePatchNumber() {
        assertEquals(TagOrder.Move.FORWARD, TagOrder.of("v0.2.99", "v0.3.0"));
        assertEquals(TagOrder.Move.BACKWARD, TagOrder.of("v1.0.0", "v0.9.9"));
    }
}
