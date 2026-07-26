package com.retro.bamfmenu.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The blue screen's classification and its copy, tested off the REAL NeoForge keys (every key string
 * here is verbatim from neoforge-21.1.235's assets/neoforge/lang/en_us.json).
 * Two of these guard reported bugs, not hypotheticals: the old screen appended "present on your client
 * but missing on the server" to EVERY case (Jordy's kick was a newer JourneyMap, so he would have read a
 * false diagnosis), and a plain package mismatch that recurred once went straight to the terminal screen.
 */
class BugcheckTest {

    private static final String WRAPPER = "neoforge.network.negotiation.failure.mod";
    private static final String EXTRA = "neoforge.network.negotiation.failure.missing.client.server";
    private static final String MISSING = "neoforge.network.negotiation.failure.missing.server.client";
    private static final String SKEW = "neoforge.network.negotiation.failure.version.mismatch";
    private static final String FLOW = "neoforge.network.negotiation.failure.flow.client.missing";

    private static Bugcheck.Failure fail(String modid, String... keys) {
        return new Bugcheck.Failure(modid, List.of(keys));
    }

    @Test
    void extraClientPackage_namesThemAsUnexpected() {
        Bugcheck c = Bugcheck.of(List.of(fail("supplementaries", EXTRA), fail("chipped", EXTRA)),
                "raw", false);
        assertEquals("BAMF_CLIENT_PACKAGE_UNEXPECTED", c.stopCode());
        assertEquals("Unexpected packages:", c.listHeader());
        assertEquals(List.of("supplementaries", "chipped"), c.rows());
        assertEquals("These packages are not present on the server.", c.consequence());
    }

    @Test
    void versionSkew_neverClaimsTheServerIsMissingThePackage() {
        Bugcheck c = Bugcheck.of(List.of(fail("journeymap", WRAPPER, SKEW)),
                "journeymap:main: The client wants the payload to be version: 2,"
                        + " but the server wants it to be version: 1!", false);
        assertEquals("BAMF_PACKAGE_REVISION_MISMATCH", c.stopCode());
        assertFalse(c.text().contains("not present on the server"),
                "a revision skew means both sides HAVE the package");
        assertFalse(c.text().contains("Unexpected packages"), () -> c.text());
    }

    @Test
    void versionSkew_neverPrintsThePayloadProtocolNumbers() {
        Bugcheck c = Bugcheck.of(List.of(fail("journeymap", WRAPPER, SKEW)),
                "journeymap:main: The client wants the payload to be version: 2,"
                        + " but the server wants it to be version: 1!", false);
        assertEquals(List.of("journeymap"), c.rows());
        assertFalse(c.text().contains("version: 2"),
                "those are payload protocol versions, printing them sends someone hunting a mod 2.0");
    }

    @Test
    void missingClientPackage_saysTheServerIsRunningThem() {
        Bugcheck c = Bugcheck.of(List.of(fail("journeymap", MISSING)), "raw", false);
        assertEquals("BAMF_CLIENT_PACKAGE_MISSING", c.stopCode());
        assertEquals("Missing packages:", c.listHeader());
        assertEquals("The server is running these and your client is not.", c.consequence());
    }

    @Test
    void wrappedReason_classifiesTheKeyUnderTheWrapper() {
        // ModMismatchDisconnectedScreen's ctor re-wraps every reason it can name a mod for, so the
        // wrapper alone must not decide anything
        Bugcheck wrapped = Bugcheck.of(List.of(fail("chipped", WRAPPER, EXTRA)), "raw", false);
        Bugcheck bare = Bugcheck.of(List.of(fail("chipped", EXTRA)), "raw", false);
        assertEquals(bare.stopCode(), wrapped.stopCode());
        assertEquals("BAMF_CLIENT_PACKAGE_UNEXPECTED", wrapped.stopCode());
    }

    @Test
    void wrapperOnItsOwn_isUnclassifiedNotTerminal() {
        Bugcheck c = Bugcheck.of(List.of(fail("chipped", WRAPPER)), "raw", false);
        assertEquals("BAMF_SYNC_REQUIRED", c.stopCode());
    }

    @Test
    void terminalChannel_outranksARepairableSibling() {
        Bugcheck c = Bugcheck.of(List.of(fail("chipped", EXTRA), fail("bamfnether", WRAPPER, FLOW)),
                "raw", false);
        assertEquals("BAMF_PROTOCOL_FAULT_NOT_HANDLED", c.stopCode());
        assertEquals("BAMFOS ran into a problem it cannot handle.", c.diagnosis());
    }

    @Test
    void plainPackageMismatchThatRecurs_isNotTheTerminalScreen() {
        Bugcheck c = Bugcheck.of(List.of(fail("supplementaries", EXTRA)), "raw", true);
        assertEquals("BAMF_SYNC_CONVERGENCE_FAILED", c.stopCode());
        assertFalse(c.text().contains("cannot handle"),
                "recurrence may sharpen the wording, it may never reclassify a package mismatch");
        assertFalse(c.text().contains("cannot repair"), "the owner rejected that wording outright");
    }

    @Test
    void repairableRecurrence_keepsTheParentsRowsAndDropsTheRestartOrder() {
        Bugcheck first = Bugcheck.of(List.of(fail("supplementaries", EXTRA)), "raw", false);
        Bugcheck again = Bugcheck.of(List.of(fail("supplementaries", EXTRA)), "raw", true);
        assertEquals(first.listHeader(), again.listHeader());
        assertEquals(first.rows(), again.rows());
        assertEquals("", again.action(), "asking for the same restart again is the loop this screen ends");
        assertTrue(again.consequence().contains("logs/latest.log"));
    }

    @Test
    void terminalRecurrence_staysTerminal() {
        Bugcheck c = Bugcheck.of(List.of(fail("bamfnether", FLOW)), "raw", true);
        assertEquals("BAMF_PROTOCOL_FAULT_NOT_HANDLED", c.stopCode());
    }

    @Test
    void twoDifferentRepairableClasses_dropToSyncRequired() {
        Bugcheck c = Bugcheck.of(List.of(fail("chipped", EXTRA), fail("journeymap", MISSING)),
                "raw", false);
        assertEquals("BAMF_SYNC_REQUIRED", c.stopCode());
        assertEquals("Packages involved:", c.listHeader(),
                "a row must never sit under a header that names the wrong fault");
    }

    @Test
    void platformNamespaces_neverBecomeRows() {
        Bugcheck c = Bugcheck.of(List.of(fail("minecraft", EXTRA), fail("neoforge", EXTRA),
                fail("forge", EXTRA), fail("chipped", EXTRA)), "raw", false);
        assertEquals(List.of("chipped"), c.rows());
    }

    @Test
    void noChannelNames_showTheRawReasonAndNoListHeader() {
        Bugcheck c = Bugcheck.of(List.of(fail("", "multiplayer.disconnect.incompatible")),
                "Incompatible client! Please use NeoForge 21.1.235", false);
        assertEquals("", c.listHeader());
        assertEquals(List.of("Incompatible client! Please use NeoForge 21.1.235"), c.rows());
        assertTrue(c.body().stream().noneMatch(b -> b.role() == Bugcheck.Role.HEADER));
    }

    @Test
    void noRows_meansNoHeaderBlockEvenWithAHeaderString() {
        Bugcheck c = new Bugcheck("d", "Unexpected packages:", List.of(), "c", "A", "BAMF_X");
        assertTrue(c.body().stream().noneMatch(b -> b.role() == Bugcheck.Role.HEADER));
    }

    @Test
    void rowsAreCappedWithAnHonestRemainder() {
        List<Bugcheck.Failure> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(fail("mod" + i, EXTRA));
        }
        Bugcheck c = Bugcheck.of(many, "raw", false);
        assertEquals(9, c.rows().size());
        assertEquals("+4 more", c.rows().get(8));
    }

    /** A game-version refusal is the PLATFORM's handshake and not a channel at all. It used to sit in the skew
     *  set, so a player on a stale profile read "Client package revision mismatch detected." with the CAPS
     *  restart order under it, and nothing the updater does can honor that: it syncs packages, not Minecraft. */
    @Test
    void aGameVersionRefusal_isNeitherARevisionSkewNorARestart() {
        for (String key : List.of("multiplayer.disconnect.outdated_client",
                "multiplayer.disconnect.outdated_server")) {
            Bugcheck c = Bugcheck.of(List.of(fail("", key)), "Incompatible client! Please use 1.21.1", false);
            assertEquals("BAMF_GAME_VERSION_MISMATCH", c.stopCode(), key);
            assertEquals("Minecraft version mismatch detected.", c.diagnosis(), key);
            assertEquals("", c.action(), key);
            assertFalse(c.text().contains("RESTART YOUR GAME"), key);
            assertFalse(c.text().contains("revision"), key);
            assertTrue(c.consequence().contains("Contact the pack owner"), key);
            assertEquals(List.of("Incompatible client! Please use 1.21.1"), c.rows(), key);
        }
    }

    /** ONE reason really can carry two opposite faults: NeoForge appends BOTH data-map directions into a single
     *  disconnect component when mandatory data maps differ each way, and reading only the first key printed
     *  "These packages are not present on the server." directly under a row saying the server had ones this
     *  client does not. That is the Jordy shape again, so two faults means neither is the whole story. */
    @Test
    void oneReasonCarryingBothDataMapDirections_dropsToTheMixedDefault() {
        String raw = "Cannot connect to server as it is missing mandatory registry data maps present on the"
                + " client: bamfnether:heat (minecraft:block)"
                + "\nCannot connect to server as it has mandatory registry data maps not present on the"
                + " client: chipped:cuts (minecraft:block)";
        Bugcheck c = Bugcheck.of(List.of(fail("", "neoforge.network.data_maps.missing_our",
                "neoforge.network.data_maps.missing_their")), raw, false);
        assertEquals("BAMF_SYNC_REQUIRED", c.stopCode());
        assertEquals("Client and server do not agree on the pack contents.", c.diagnosis());
        assertFalse(c.text().contains("not present on the server."), () -> c.text());
        assertFalse(c.text().contains("The server is running these and your client is not."), () -> c.text());
    }

    /** The consequence may not deny what the rows show. A repairable channel can be listed two lines above it,
     *  so "This is not a package or revision difference" was disproved by the screen's own evidence. */
    @Test
    void terminalVariant_neverDeniesTheRepairableSiblingInItsOwnRows() {
        Bugcheck c = Bugcheck.of(List.of(fail("chipped", EXTRA), fail("bamfnether", WRAPPER, FLOW)),
                "Mod channels failed to negotiate with the server."
                        + "\nchipped:main: This channel is missing on the server side, but required on the client!"
                        + "\nbamfnether:main: The client wants the payload to flow: PLAY_TO_SERVER,"
                        + " but the server does not support it!", false);
        assertTrue(c.rows().stream().anyMatch(r -> r.contains("chipped:main")), "the repairable row is on screen");
        assertFalse(c.text().contains("not a package or revision difference"), () -> c.text());
        assertTrue(c.consequence().startsWith("A sync will not clear this on its own."), c.consequence());
    }

    /** The CAPS order promises a change only the breadcrumb can deliver. With no crumb the next boot
     *  quarantines nothing AND writes no history entry, so the recurrence escalation never arms either and the
     *  friend restarts into this exact screen forever. */
    @Test
    void anUnwrittenBreadcrumb_dropsTheRestartOrderForTheContactLine() {
        Bugcheck c = Bugcheck.of(List.of(fail("chipped", EXTRA)), "raw", false).unrecorded();
        assertEquals("", c.action());
        assertTrue(c.body().stream().noneMatch(b -> b.role() == Bugcheck.Role.ACTION));
        assertFalse(c.text().contains("RESTART YOUR GAME"));
        assertTrue(c.consequence().contains("logs/latest.log"));
        assertEquals("BAMF_CLIENT_PACKAGE_UNEXPECTED", c.stopCode(), "same fault, only the instruction changes");
        assertEquals(List.of("chipped"), c.rows());
        Bugcheck terminal = Bugcheck.sample("terminal");
        assertEquals(terminal.consequence(), terminal.unrecorded().consequence(),
                "a screen that never had an order keeps its own sentence, it already says contact the owner");
    }

    @Test
    void terminalVariant_namesTheOwnerAndTheLog() {
        Bugcheck c = Bugcheck.of(List.of(fail("bamfnether", FLOW)), "raw", false);
        assertTrue(c.consequence().contains("Contact the pack owner"));
        assertTrue(c.consequence().contains("logs/latest.log"));
    }

    @Test
    void terminalVariant_hasNoCapsActionLine() {
        Bugcheck c = Bugcheck.of(List.of(fail("bamfnether", FLOW)), "raw", false);
        assertEquals("", c.action());
        assertTrue(c.body().stream().noneMatch(b -> b.role() == Bugcheck.Role.ACTION));
        assertFalse(c.text().contains("RESTART YOUR GAME"),
                "a restart cannot clear a protocol fault, so the CAPS order would be a lie");
    }

    @Test
    void registriesWithUnknownKeysFromTheServer_isAMissingPackage() {
        Bugcheck c = Bugcheck.of(
                List.of(fail("", "neoforge.network.registries.sync.server-with-unknown-keys")),
                "The server send registries with unknown keys: journeymap:waypoints", false);
        assertEquals("BAMF_CLIENT_PACKAGE_MISSING", c.stopCode());
    }

    @Test
    void serverAnnouncedRegistriesItNeverSent_isNotTerminal() {
        // the server said "expect these" and then did not send them, which is neither a package set nor
        // a revision, so it must not claim to be either and must not claim to be unhandleable
        Bugcheck c = Bugcheck.of(List.of(fail("", "neoforge.network.registries.sync.missing")),
                "Not all expected registries were received from the server! (missing: x)", false);
        assertEquals("BAMF_SYNC_REQUIRED", c.stopCode());
    }

    @Test
    void vanillaServerWithoutNeoforge_isTerminal() {
        Bugcheck c = Bugcheck.of(
                List.of(fail("", "neoforge.network.negotiation.failure.vanilla.server.not_supported")),
                "raw", false);
        assertEquals("BAMF_PROTOCOL_FAULT_NOT_HANDLED", c.stopCode());
    }

    @Test
    void everyRepairablePackageVariant_asksForTheRestart() {
        for (String key : List.of(EXTRA, MISSING, SKEW, "multiplayer.disconnect.incompatible")) {
            Bugcheck c = Bugcheck.of(List.of(fail("chipped", key)), "raw", false);
            assertEquals("RESTART YOUR GAME SO THESE CHANGES CAN BE APPLIED.", c.action(), key);
            assertFalse(c.text().contains("cannot handle"), key);
        }
    }

    @Test
    void everyVariant_endsOnAStopCode() {
        for (boolean recurred : List.of(false, true)) {
            for (String key : List.of(EXTRA, MISSING, SKEW, FLOW, "multiplayer.disconnect.incompatible")) {
                Bugcheck c = Bugcheck.of(List.of(fail("chipped", key)), "raw", recurred);
                List<Bugcheck.Block> body = c.body();
                assertEquals(Bugcheck.Role.STOP, body.get(body.size() - 1).role(), key);
                assertTrue(body.get(body.size() - 1).text().startsWith("*** STOP: BAMF_"), key);
            }
        }
    }

    @Test
    void everySampleLandsOnTheVariantItsNameClaims() {
        // the demo hook and the offline renderer both read these, so a sample that classifies somewhere
        // else means the pack host signed off on a screen no player can get
        assertEquals("BAMF_CLIENT_PACKAGE_UNEXPECTED", Bugcheck.sample("extra").stopCode());
        assertEquals("BAMF_CLIENT_PACKAGE_MISSING", Bugcheck.sample("missing").stopCode());
        assertEquals("BAMF_PACKAGE_REVISION_MISMATCH", Bugcheck.sample("skew").stopCode());
        assertEquals("BAMF_SYNC_REQUIRED", Bugcheck.sample("unclassified").stopCode());
        assertEquals("BAMF_GAME_VERSION_MISMATCH", Bugcheck.sample("platform").stopCode());
        assertEquals("BAMF_PROTOCOL_FAULT_NOT_HANDLED", Bugcheck.sample("terminal").stopCode());
        assertEquals("BAMF_SYNC_CONVERGENCE_FAILED", Bugcheck.sample("recurrence").stopCode());
    }

    @Test
    void theSkewSampleShowsAPackageNameAndNotTheProtocolNumbers() {
        Bugcheck c = Bugcheck.sample("skew");
        assertEquals(List.of("journeymap"), c.rows());
        assertFalse(c.text().contains("version: 2"), () -> c.text());
    }

    private static final ToIntFunction<Bugcheck.Block> ONE_LINE = b -> 1;

    private static Bugcheck eightRows() {
        List<Bugcheck.Failure> many = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            many.add(fail("mod" + i, EXTRA));
        }
        return Bugcheck.of(many, "raw", false);
    }

    @Test
    void fit_dropsTheBreathingRoomBeforeAnyWords() {
        List<Bugcheck.Block> body = eightRows().body();
        long words = body.stream().filter(b -> b.role() != Bugcheck.Role.GAP).count();
        List<Bugcheck.Block> kept = Bugcheck.fit(body, ONE_LINE, (int) words);
        assertEquals(words, kept.size());
        assertTrue(kept.stream().noneMatch(b -> b.role() == Bugcheck.Role.GAP));
    }

    @Test
    void fit_collapsesTheRowTailIntoAnHonestCount() {
        List<Bugcheck.Block> kept = Bugcheck.fit(eightRows().body(), ONE_LINE, 10);
        List<String> rows = kept.stream().filter(b -> b.role() == Bugcheck.Role.ROW)
                .map(Bugcheck.Block::text).toList();
        assertEquals(List.of("mod0", "mod1", "mod2", "+5 more"), rows);
        assertEquals(10, kept.size());
    }

    @Test
    void fit_neverDropsTheDiagnosisTheActionOrTheStopCode() {
        List<Bugcheck.Block> kept = Bugcheck.fit(eightRows().body(), ONE_LINE, 1);
        List<Bugcheck.Role> roles = kept.stream().map(Bugcheck.Block::role).toList();
        assertEquals(List.of(Bugcheck.Role.DIAGNOSIS, Bugcheck.Role.ACTION, Bugcheck.Role.STOP), roles);
    }

    @Test
    void fit_keepsTheContactInstructionWhenThereIsNoActionLine() {
        // the cannot-handle screen has no CAPS order, so its consequence is the only thing that tells the
        // player what to do; a cramped window may not be what deletes it
        Bugcheck c = Bugcheck.sample("terminal");
        List<Bugcheck.Block> kept = Bugcheck.fit(c.body(), ONE_LINE, 2);
        assertTrue(kept.stream().anyMatch(b -> b.role() == Bugcheck.Role.CONSEQUENCE
                && b.text().contains("logs/latest.log")));
        assertTrue(kept.stream().anyMatch(b -> b.role() == Bugcheck.Role.STOP));
    }

    @Test
    void fit_leavesAFittingBodyAlone() {
        List<Bugcheck.Block> body = eightRows().body();
        assertEquals(body, Bugcheck.fit(body, ONE_LINE, body.size()));
    }

    private static Bugcheck twelveCulprits() {
        List<Bugcheck.Failure> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(fail("mod" + i, EXTRA));
        }
        return Bugcheck.of(many, "raw", false);
    }

    /**
     * cap() and fit() both shorten the row list, and their counts have to COMPOSE: whatever the window, the
     * names on screen plus the number in "+N more" is the number of culprits the kick named. cap()'s remainder
     * row sits last, so it is the first thing the collapse loop reaches, and shedding it as an ordinary row
     * replaced the honest count with a smaller one on every window that collapsed at all. 1080p at GUI scale 4
     * is one of them, so the pack host reads a screenshot and chases the wrong number of mods.
     */
    @Test
    void everyWindow_accountsForAllTwelveCulprits() {
        for (int budget = 10; budget <= 15; budget++) {
            List<Bugcheck.Block> kept = Bugcheck.fit(twelveCulprits().body(), ONE_LINE, budget);
            List<String> rows = kept.stream().filter(b -> b.role() == Bugcheck.Role.ROW)
                    .map(Bugcheck.Block::text).toList();
            int named = 0;
            int hidden = 0;
            for (String row : rows) {
                if (row.endsWith(" more")) {
                    hidden += Integer.parseInt(row.substring(1, row.indexOf(' ')));
                } else {
                    named++;
                }
            }
            assertEquals(12, named + hidden, "budget " + budget + " printed " + rows);
            assertTrue(kept.size() <= budget, "and it still fits: budget " + budget + " kept " + kept.size());
        }
    }

    /** A body that lands exactly on budget after the TECH shed keeps every row: the line the "+N more" row
     *  costs may only be reserved once a drop has actually HAPPENED, else the screen trades a real culprit for a
     *  row whose whole job is to say one is missing. */
    @Test
    void fit_keepsEveryRowWhenTheBodyLandsExactlyOnBudget() {
        List<Bugcheck.Block> body = eightRows().body();
        int budget = (int) body.stream().filter(b -> b.role() != Bugcheck.Role.GAP
                && b.role() != Bugcheck.Role.TECH).count();
        List<String> rows = Bugcheck.fit(body, ONE_LINE, budget).stream()
                .filter(b -> b.role() == Bugcheck.Role.ROW).map(Bugcheck.Block::text).toList();
        assertEquals(List.of("mod0", "mod1", "mod2", "mod3", "mod4", "mod5", "mod6", "mod7"), rows);
    }
}
