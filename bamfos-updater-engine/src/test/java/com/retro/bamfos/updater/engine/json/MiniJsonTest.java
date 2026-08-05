package com.retro.bamfos.updater.engine.json;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Bad \\u escapes must throw JsonError, not NumberFormatException. Journal.load, Ledger.load, and
 * ManifestParser all catch JsonError to recover or wrap errors cleanly instead of crashing step 0.
 */
class MiniJsonTest {

    @Test
    void aRealEscapeStillDecodes() {
        assertEquals(Map.of("k", "A\u00e9"), MiniJson.parse("{\"k\":\"\\u0041\\u00e9\"}"));
    }

    @Test
    void aNonHexEscapeIsAJsonError_neverANumberFormatException() {
        assertThrows(MiniJson.JsonError.class, () -> MiniJson.parse("{\"k\":\"\\uZZZZ\"}"),
                "the corrupt-file recovery paths catch JsonError and nothing else");
    }

    /** Integer.parseInt(s, 16) accepts a leading sign, so reject things like "\\u-123". */
    @Test
    void aSignedEscapeIsRefused_notQuietlyDecoded() {
        assertThrows(MiniJson.JsonError.class, () -> MiniJson.parse("{\"k\":\"\\u-123\"}"));
        assertThrows(MiniJson.JsonError.class, () -> MiniJson.parse("{\"k\":\"\\u+123\"}"));
    }

    /** Reject non-ASCII digits (like fullwidth '3') that Character.digit accepts as hex. */
    @Test
    void aUnicodeDigitIsNotAHexDigit() {
        assertThrows(MiniJson.JsonError.class, () -> MiniJson.parse("{\"k\":\"\\u\uff13\uff13\uff13\uff13\"}"));
    }
}
