package com.retro.bamfmenu.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Tests KickReason mismatch detection against real NeoForge keys and operator/proxy literal kicks.
class KickReasonTest {

    @Test
    void everyReasonNeoforgeAndVanillaSend_isAMismatch() {
        assertTrue(KickReason.isMismatch(List.of("neoforge.network.negotiation.failure.mod",
                "neoforge.network.negotiation.failure.missing.client.server"), "whatever"));
        assertTrue(KickReason.isMismatch(List.of("multiplayer.disconnect.incompatible"),
                "Incompatible client! Please use NeoForge 21.1.235"));
        assertTrue(KickReason.isMismatch(List.of("multiplayer.disconnect.outdated_client"),
                "Incompatible client! Please use 1.21.1"));
        assertTrue(KickReason.isMismatch(List.of("multiplayer.disconnect.missing_tags"),
                "Incomplete set of tags received from server."));
        // mandatory data map kicks put translatable siblings under an empty literal root, so we have to
        // walk the full component tree to find the keys
        assertTrue(KickReason.isMismatch(List.of("neoforge.network.data_maps.missing_their"),
                "Cannot connect to server as it has mandatory registry data maps not present on the client: x"));
    }

    /** Normal kicks stay on the vanilla screen: once a kick has a translation key, we don't fall back to
     *  scanning its text even if it mentions mismatch words. */
    @Test
    void anOrdinaryKeyedKick_isNotAMismatch() {
        assertFalse(KickReason.isMismatch(List.of("multiplayer.disconnect.kicked"), "Kicked by an operator"));
        assertFalse(KickReason.isMismatch(List.of("multiplayer.disconnect.banned"),
                "You are banned: registry mismatch talk in chat"));
    }

    // Plain-text operator kicks like `/kick Jordy restarting to fix a registry mismatch, back in 2` must never trigger quarantine.
    @Test
    void anOperatorsProseKick_neverArmsTheQuarantine() {
        assertFalse(KickReason.isMismatch(List.of(),
                "restarting to fix a registry mismatch, back in 2"));
        assertFalse(KickReason.isMismatch(List.of(), "Incompatible with our rules, go read them"));
        assertFalse(KickReason.isMismatch(List.of(), "server restarting for a neoforge update"));
        assertFalse(KickReason.isMismatch(List.of(), ""));
        assertFalse(KickReason.isMismatch(List.of(), null));
    }

    // Kicks naming a mod channel count as mismatch evidence; URLs and host:port proxy addresses don't.
    @Test
    void aLiteralKickNamingAChannel_isStillAMismatch() {
        assertTrue(KickReason.isMismatch(List.of(),
                "Mod channels failed: chipped:main is missing on the server side"));
        assertFalse(KickReason.isMismatch(List.of(), "You were kicked, see https://wiki.example/rules"));
        assertFalse(KickReason.isMismatch(List.of(), "reconnect to play.example.net:25565"));
    }
}
