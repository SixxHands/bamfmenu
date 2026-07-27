package com.retro.bamfmenu.early.core.repair;

import com.retro.bamfmenu.early.core.repair.input.TextEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests TextEntry mid-string edits, deletion, and scrolling in narrow panels. */
class TextEntryTest {

    private static TextEntry typed(String s) {
        TextEntry f = new TextEntry("", TextEntry.MAX);
        s.codePoints().forEach(f::type);
        return f;
    }

    @Test
    void opensHoldingTheCurrentValueWithTheCaretAtTheEnd() {
        TextEntry f = new TextEntry("1.2.3.4", TextEntry.MAX);
        assertEquals("1.2.3.4", f.value());
        assertEquals(7, f.caret());
    }

    /** Supports mid-string edits so players can fix an address without deleting back to the typo. */
    @Test
    void aDigitCanBeFixedInTheMiddleWithoutRetypingTheRest() {
        TextEntry f = new TextEntry("10.0.0.5", TextEntry.MAX);
        f.left();        // caret now sits between the last dot and the 5
        f.erase();       // Deletes '.' rather than '5' since backspace acts relative to the caret.
        assertEquals("10.0.05", f.value());
        f.type('.');
        assertEquals("10.0.0.5", f.value());
        assertEquals(7, f.caret());
    }

    @Test
    void backspaceTakesTheCharacterBeforeTheCaretAndStopsAtTheStart() {
        TextEntry f = typed("abc");
        f.erase();
        assertEquals("ab", f.value());
        f.left();
        f.erase();
        assertEquals("b", f.value());
        f.erase();
        f.erase();
        assertEquals("b", f.value());
        assertEquals(0, f.caret());
    }

    @Test
    void theCaretCannotWalkOffEitherEnd() {
        TextEntry f = typed("ab");
        f.right();
        f.right();
        assertEquals(2, f.caret());
        f.left();
        f.left();
        f.left();
        assertEquals(0, f.caret());
        f.type('X');
        assertEquals("Xab", f.value());
    }

    /** Rejects characters the terminal font cannot draw instead of accepting invisible glyphs. */
    @Test
    void onlyPrintableAsciiGetsIn() {
        TextEntry f = typed("ok");
        f.type('\n');
        f.type('\t');
        f.type(0x1B);
        f.type('é');
        f.type(0x1F600); // Emoji (outside ASCII range).
        assertEquals("ok", f.value());
        f.type('~');
        f.type(' ');
        assertEquals("ok~ ", f.value());
    }

    @Test
    void theFieldStopsAcceptingAtItsLimitAndTheLimitIsSane() {
        TextEntry f = new TextEntry("", 5);
        "abcdefghij".codePoints().forEach(f::type);
        assertEquals("abcde", f.value());
        assertEquals(5, f.caret());
        // an IPv6 literal with brackets and a port still fits the default
        String v6 = "[2001:0db8:85a3:0000:0000:8a2e:0370:7334]:25565";
        TextEntry big = new TextEntry(v6, TextEntry.MAX);
        assertEquals(v6, big.value());
    }

    /** An initial value longer than the cap is truncated rather than throwing on the way in. */
    @Test
    void anOverlongStartingValueIsTrimmedNotFatal() {
        TextEntry f = new TextEntry("abcdefghij", 4);
        assertEquals("abcd", f.value());
        assertEquals(4, f.caret());
    }

    @Test
    void theCaretIsDrawnWhereItActuallyIs() {
        TextEntry f = typed("abc");
        assertEquals("abc_", f.visible(20));
        f.left();
        assertEquals("ab_c", f.visible(20));
    }

    /** Narrow panels must pan the visible window with the caret instead of clipping typed characters. */
    @Test
    void aLongValueScrollsToKeepTheCaretVisible() {
        TextEntry f = typed("aaaaaaaaaabbbbbbbbbbcccccccccc"); // 30 chars, caret at the end
        String tail = f.visible(10);
        assertEquals(10, tail.length());
        assertTrue(tail.endsWith("_"));

        for (int i = 0; i < 25; i++) {
            f.left();
        }
        String middle = f.visible(10);
        assertEquals(10, middle.length());
        assertTrue(middle.contains("_"));
    }

    @Test
    void aValueShorterThanTheWindowIsNotPadded() {
        TextEntry f = typed("hi");
        assertEquals("hi_", f.visible(40));
    }
}
