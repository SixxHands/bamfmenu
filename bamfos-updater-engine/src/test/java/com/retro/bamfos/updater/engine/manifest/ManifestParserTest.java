package com.retro.bamfos.updater.engine.manifest;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestParserTest {

    private static String example() throws IOException {
        try (InputStream in = ManifestParserTest.class.getResourceAsStream("/manifest-example.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesTheSpecExample() throws IOException {
        Manifest m = ManifestParser.parse(example());
        assertEquals(1, m.schemaVersion());
        assertEquals("bamfos", m.pack().id());
        assertEquals("v0.2.5", m.pack().version());
        assertEquals("21.1.235", m.pack().neoforge());
        assertEquals(1, m.pack().updaterAbi());
        assertEquals(java.util.List.of(1L), m.pack().engineSchemaSupport());
        assertFalse(m.requiresRelaunch());
        assertEquals(java.util.List.of("chipped", "envelope"), m.removedModids());
        assertEquals(6, m.files().size());

        FileEntry zip = m.files().get(0);
        assertEquals(FileEntry.Source.Type.RELEASE_ZIP, zip.source().type());
        assertEquals("config/bcc-common.toml", zip.source().inner());

        FileEntry thisRelease = m.files().get(1);
        assertNull(thisRelease.source().release()); // absent backref = asset on THIS release

        FileEntry backref = m.files().get(2);
        assertEquals("v0.2.4", backref.source().release()); // incremental model

        FileEntry optional = m.files().get(3);
        assertTrue(optional.installDisabled());
        assertEquals("optional", optional.category());
        assertEquals(FileEntry.OnRemove.ORPHAN, optional.onRemove());

        FileEntry seed = m.files().get(4);
        assertEquals(FileEntry.Policy.SEED_ONCE, seed.policy());

        FileEntry url = m.files().get(5);
        assertEquals(FileEntry.Source.Type.URL, url.source().type());
        assertEquals(FileEntry.Clazz.RELAUNCH, url.clazz());
    }

    @Test
    void unknownFieldsAreIgnored() throws IOException {
        // Fixture includes an unknown future field; parsing it without throwing is the whole test.
        ManifestParser.parse(example());
    }

    @Test
    void schemaTooNewIsItsOwnState() {
        String j = "{\"schema_version\": 99, \"pack\": {}, \"requires_relaunch\": false, \"files\": []}";
        ManifestParser.SchemaTooNew e = assertThrows(ManifestParser.SchemaTooNew.class, () -> ManifestParser.parse(j));
        assertEquals(99, e.schemaVersion);
    }

    @Test
    void errorsNameTheExactField() throws IOException {
        String missingSha = example().replaceFirst("\"sha256\": \"be69f4039811b60f0b3af544c79a4a47de53c8ff8c3f705c8c3ad8f21d5d9dfe\",", "");
        ManifestParser.ManifestError e1 = assertThrows(ManifestParser.ManifestError.class, () -> ManifestParser.parse(missingSha));
        assertTrue(e1.getMessage().contains("files[0].sha256"), e1.getMessage());

        String badHex = example().replace("be69f4039811b60f0b3af544c79a4a47de53c8ff8c3f705c8c3ad8f21d5d9dfe", "NOT-HEX");
        ManifestParser.ManifestError e2 = assertThrows(ManifestParser.ManifestError.class, () -> ManifestParser.parse(badHex));
        assertTrue(e2.getMessage().contains("files[0].sha256"), e2.getMessage());

        String badSide = example().replaceFirst("\"side\": \"both\"", "\"side\": \"everywhere\"");
        ManifestParser.ManifestError e3 = assertThrows(ManifestParser.ManifestError.class, () -> ManifestParser.parse(badSide));
        assertTrue(e3.getMessage().contains(".side"), e3.getMessage());
    }

    @Test
    void caseInsensitiveDuplicatePathsAreRejectedWhenSidesIntersect() throws IOException {
        // files[0] is side both; a case-variant of its path on side client intersects -> one Windows file
        String dup = example().replace("\"path\": \"mods/bamfmenu-1.0.3.jar\"", "\"path\": \"CONFIG/bcc-common.TOML\"");
        ManifestParser.ManifestError e = assertThrows(ManifestParser.ManifestError.class, () -> ManifestParser.parse(dup));
        assertTrue(e.getMessage().contains("collides case-insensitively"), e.getMessage());
    }

    @Test
    void samePathOnDisjointSidesIsTheDesignedVariantMechanism() {
        // the ModernFix case: one install path, two side-specific truths, MUST parse
        String j = """
                {"schema_version": 1,
                 "pack": {"id":"bamfos","version":"v1","mc":"1.21.1","neoforge":"21.1.235","updater_abi":1},
                 "requires_relaunch": false,
                 "files": [
                   {"path":"config/modernfix-mixins.properties","sha256":"%s","size":1,"side":"client","policy":"managed",
                    "source":{"type":"release-zip","asset":"data-client.zip","inner":"config/modernfix-mixins.properties"}},
                   {"path":"config/modernfix-mixins.properties","sha256":"%s","size":1,"side":"server","policy":"managed",
                    "source":{"type":"release-zip","asset":"data-server.zip","inner":"config/modernfix-mixins.properties"}}
                 ]}""".formatted("0".repeat(64), "1".repeat(63) + "a");
        Manifest m = ManifestParser.parse(j);
        assertEquals(2, m.files().size());
    }

    @Test
    void traversalPathsAreRejectedWithTheRule() throws IOException {
        String traversal = example().replace("\"path\": \"mods/bamfmenu-1.0.3.jar\"", "\"path\": \"mods/../../boom.jar\"");
        ManifestParser.ManifestError e = assertThrows(ManifestParser.ManifestError.class, () -> ManifestParser.parse(traversal));
        assertTrue(e.getMessage().contains("'..' segment"), e.getMessage());
    }

    @Test
    void notJsonIsAnActionableError() {
        ManifestParser.ManifestError e = assertThrows(ManifestParser.ManifestError.class, () -> ManifestParser.parse("not json at all"));
        assertTrue(e.getMessage().contains("not valid JSON"), e.getMessage());
    }
}
