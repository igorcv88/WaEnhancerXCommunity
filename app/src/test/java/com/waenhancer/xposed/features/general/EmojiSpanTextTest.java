package com.waenhancer.xposed.features.general;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EmojiSpanTextTest {
    private static boolean matches(String value) {
        return EmojiSpanText.isEmojiOnly(value, 0, value.length());
    }

    @Test public void acceptsColorEmojiAndSequences() {
        assertTrue(matches("🫩"));
        assertTrue(matches("😍"));
        assertTrue(matches("❤️"));
        assertTrue(matches("🧑🏽‍⚕️"));
        assertTrue(matches("🇧🇷"));
        assertTrue(matches("1️⃣"));
        assertTrue(matches("#️⃣"));
    }

    @Test public void refusesOrdinaryTextAndOtherSpans() {
        assertFalse(matches(""));
        assertFalse(matches("A"));
        assertFalse(matches("1"));
        assertFalse(matches("123"));
        assertFalse(matches("Hi 😍"));
        assertFalse(matches("1️⃣2"));
        assertFalse(matches("x🧑🏽‍⚕️"));
    }

    @Test public void validatesSpanBounds() {
        assertFalse(EmojiSpanText.isEmojiOnly("😀", -1, 2));
        assertFalse(EmojiSpanText.isEmojiOnly("😀", 0, 3));
        assertTrue(EmojiSpanText.isEmojiOnly("x😍y", 1, 3));
    }
}
