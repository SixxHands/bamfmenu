package com.retro.bamfmenu.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Tests Bugcheck classification and copy against real NeoForge translation keys (including Jordy's JourneyMap mismatch case).
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
        assertFalse(c.text().contains("not present on the server"));
        assertFalse(c.text().contains("Unexpected packages"), () -> c.text());
    }

    @Test
    void versionSkew_neverPrintsThePayloadProtocolNumbers() {
        Bugcheck c = Bugcheck.of(List.of(fail("journeymap", WRAPPER, SKEW)),
                "journeymap:main: The client wants the payload to be version: 2,"
                        + " but the server wants it to be version: 1!", false);
        assertEquals(List.of("journeymap"), c.rows());
        assertFalse(c.text().contains("version: 2"));
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
        // ModMismatchDisconnectedScreen wraps every reason that names a mod, so don't classify based on
        // the wrapper key alone
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
        assertFalse(c.text().contains("cannot handle"));
        assertFalse(c.text().contains("cannot repair"));
    }

    @Test
    void repairableRecurrence_keepsTheParentsRowsAndDropsTheRestartOrder() {
        Bugcheck first = Bugcheck.of(List.of(fail("supplementaries", EXTRA)), "raw", false);
        Bugcheck again = Bugcheck.of(List.of(fail("supplementaries", EXTRA)), "raw", true);
        assertEquals(first.listHeader(), again.listHeader());
        assertEquals(first.rows(), again.rows());
        assertEquals("", again.action());
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
        assertEquals("Packages involved:", c.listHeader());
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

    // Game-version mismatch must not tell the player to restart for a package sync.
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

    // Mixed opposite data-map faults (Jordy case) must fall back to the generic sync message.
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

    /** Don't claim "not a package or revision difference" when a repairable channel is listed right above it. */
    @Test
    void terminalVariant_neverDeniesTheRepairableSiblingInItsOwnRows() {
        Bugcheck c = Bugcheck.of(List.of(fail("chipped", EXTRA), fail("bamfnether", WRAPPER, FLOW)),
                "Mod channels failed to negotiate with the server."
                        + "\nchipped:main: This channel is missing on the server side, but required on the client!"
                        + "\nbamfnether:main: The client wants the payload to flow: PLAY_TO_SERVER,"
                        + " but the server does not support it!", false);
        assertTrue(c.rows().stream().anyMatch(r -> r.contains("chipped:main")));
        assertFalse(c.text().contains("not a package or revision difference"), () -> c.text());
        assertTrue(c.consequence().startsWith("A sync will not clear this on its own."), c.consequence());
    }

    // Drops the restart prompt when the breadcrumb couldn't be written so the player doesn't loop forever.
    @Test
    void anUnwrittenBreadcrumb_dropsTheRestartOrderForTheContactLine() {
        Bugcheck c = Bugcheck.of(List.of(fail("chipped", EXTRA)), "raw", false).unrecorded();
        assertEquals("", c.action());
        assertTrue(c.body().stream().noneMatch(b -> b.role() == Bugcheck.Role.ACTION));
        assertFalse(c.text().contains("RESTART YOUR GAME"));
        assertTrue(c.consequence().contains("logs/latest.log"));
        assertEquals("BAMF_CLIENT_PACKAGE_UNEXPECTED", c.stopCode());
        assertEquals(List.of("chipped"), c.rows());
        Bugcheck terminal = Bugcheck.sample("terminal");
        assertEquals(terminal.consequence(), terminal.unrecorded().consequence());
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
        assertFalse(c.text().contains("RESTART YOUR GAME"));
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
        // server advertised registries it never sent: not a mod/version mismatch and not unhandleable either
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
        // the demo hook and offline renderer both read these, so a misclassified sample means the
        // offline preview shows a screen no player can actually hit
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
        // cannot-handle has no restart prompt, so the consequence text is the only instruction and shouldn't
        // get dropped on small windows
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

    // cap() and fit() counts must compose so displayed names + "+N more" always equals total culprits.
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
            assertEquals(12, named + hidden, String.valueOf(budget) + " / " + String.valueOf(rows));
            assertTrue(kept.size() <= budget, String.valueOf(budget) + " / " + String.valueOf(kept.size()));
        }
    }

    // Only reserve a line for "+N more" once a row is actually dropped.
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
