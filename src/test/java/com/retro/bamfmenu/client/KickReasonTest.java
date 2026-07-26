package com.retro.bamfmenu.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The front door to the blue screen AND to the breadcrumb, so this is the gate that decides whether the engine
 * gets to rename jars on the next boot. Every key string here is verbatim from the game (vanilla en_us or
 * neoforge-21.1.235's own lang), and the literal cases are the shapes a proxy or an operator really sends.
 */
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
        // the mandatory-data-map refusal builds translatable SIBLINGS under an empty literal root, so the root
        // reads as plain text and only the collected key chain proves what it is
        assertTrue(KickReason.isMismatch(List.of("neoforge.network.data_maps.missing_their"),
                "Cannot connect to server as it has mandatory registry data maps not present on the client: x"));
    }

    /** An ordinary kick keeps the vanilla screen: it carries its own key, and a keyed reason never gets a
     *  second vote off its prose however many mismatch words the prose happens to contain. */
    @Test
    void anOrdinaryKeyedKick_isNotAMismatch() {
        assertFalse(KickReason.isMismatch(List.of("multiplayer.disconnect.kicked"), "Kicked by an operator"));
        assertFalse(KickReason.isMismatch(List.of("multiplayer.disconnect.banned"),
                "You are banned: registry mismatch talk in chat"));
    }

    /**
     * The one that mattered. `/kick Jordy restarting to fix a registry mismatch, back in 2` is a LITERAL
     * component, and the old text fallback matched on the bare word `registry`. That wrote the breadcrumb, and
     * the engine's harvest has no channel to narrow by in prose, so it falls to a whole-word sweep of the whole
     * sentence and disables any untracked jar whose modid is one of those words. Two of the five old keywords
     * are in this sentence, so a count of keywords would not have closed it either.
     */
    @Test
    void anOperatorsProseKick_neverArmsTheQuarantine() {
        assertFalse(KickReason.isMismatch(List.of(),
                "restarting to fix a registry mismatch, back in 2"));
        assertFalse(KickReason.isMismatch(List.of(), "Incompatible with our rules, go read them"));
        assertFalse(KickReason.isMismatch(List.of(), "server restarting for a neoforge update"));
        assertFalse(KickReason.isMismatch(List.of(), ""));
        assertFalse(KickReason.isMismatch(List.of(), null));
    }

    /** A literal kick that names a CHANNEL is real evidence, because a namespace is a mod on disk. The two false
     *  namespace shapes are the ones a proxy kick actually carries: a link and a reconnect address. */
    @Test
    void aLiteralKickNamingAChannel_isStillAMismatch() {
        assertTrue(KickReason.isMismatch(List.of(),
                "Mod channels failed: chipped:main is missing on the server side"));
        assertFalse(KickReason.isMismatch(List.of(), "You were kicked, see https://wiki.example/rules"),
                "a url's scheme is not a namespace");
        assertFalse(KickReason.isMismatch(List.of(), "reconnect to play.example.net:25565"),
                "a host:port is not a channel, a channel path is never all digits");
    }
}
