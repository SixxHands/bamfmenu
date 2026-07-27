package com.retro.bamfos.updater.watchdog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the countdown MOTD wording and the rules that keep it from outliving the restart window. */
class UpdateMotdTest {

    /** Pins the exact player-facing MOTD copy so it does not drift to generic restart text. */
    @Test
    void namesTheReleaseTheOwnersWay() {
        assertEquals("Updating the server to v0.2.6!", UpdateMotd.updating("v0.2.6"));
    }

    /** Empty tag (like manual /bamfos restart now) should still format cleanly with no blank placeholder. */
    @Test
    void saysSomethingTrueWithNoTag() {
        assertEquals("The server is updating!", UpdateMotd.updating(""));
    }

    /** The server list shows exactly two lines, so verify the MOTD always has two lines. */
    @Test
    void isTwoLines() {
        String motd = UpdateMotd.motd("v0.2.6");
        assertEquals(2, motd.split("\n", -1).length, motd);
        assertTrue(motd.startsWith("Updating the server to v0.2.6!\n"), motd);
        assertTrue(motd.endsWith("Restarting shortly to apply it"), motd);
    }

    /**
     * Checks every Decision enum value so only countdown decisions take the MOTD,
     * forcing any new decision to opt in explicitly.
     */
    @Test
    void onlyARealCountdownTakesIt() {
        for (WatchdogBrain.Decision d : WatchdogBrain.Decision.values()) {
            assertEquals(d == WatchdogBrain.Decision.RESTART, UpdateMotd.takes(d), d.name());
        }
    }

    @Test
    void givesTheOriginalBack() {
        String ours = UpdateMotd.motd("v0.2.6");
        assertEquals("A BAMFOS server", UpdateMotd.restored(ours, ours, "A BAMFOS server"));
    }

    /** If something else changed the MOTD during the countdown, cancelling leaves that MOTD alone. */
    @Test
    void leavesSomebodyElsesLineAlone() {
        String ours = UpdateMotd.motd("v0.2.6");
        assertEquals("event server, come play", UpdateMotd.restored("event server, come play", ours, "old"));
    }
}
