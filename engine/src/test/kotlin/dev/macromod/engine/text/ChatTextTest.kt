package dev.macromod.engine.text

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatTextTest {
    @Test fun `plain text is unchanged`() {
        assertEquals("hello world", stripFormattingCodes("hello world"))
    }

    @Test fun `a single section code is removed`() {
        assertEquals("hello", stripFormattingCodes("§ahello"))
    }

    @Test fun `interleaved codes are all removed`() {
        // The same case the `strip` action test pins, since both now share stripFormattingCodes.
        assertEquals("hello", stripFormattingCodes("§ahel§rlo"))
    }

    @Test fun `multiple leading codes and a reset are removed`() {
        assertEquals("Team Chat", stripFormattingCodes("§l§6Team §rChat"))
    }

    @Test fun `empty string stays empty`() {
        assertEquals("", stripFormattingCodes(""))
    }

    @Test fun `a trailing lone section sign is preserved`() {
        // `§.` needs a following char, so a dangling `§` at the end matches nothing and is kept —
        // byte-identical to how the existing strip action behaves.
        assertEquals("hi§", stripFormattingCodes("hi§"))
    }

    @Test fun `ampersand colour codes convert to section codes`() {
        assertEquals("§chi", convertAmpCodes("&chi"))
        assertEquals("§l§6Team §rChat", convertAmpCodes("&l&6Team &rChat"))
    }

    @Test fun `a doubled ampersand is an escaped literal`() {
        assertEquals("&c", convertAmpCodes("&&c")) // escaped — not treated as a colour code
        assertEquals("&", convertAmpCodes("&&"))
    }

    @Test fun `invalid or dangling ampersands are left untouched`() {
        assertEquals("&z", convertAmpCodes("&z")) // z is not a valid code char
        assertEquals("a & b", convertAmpCodes("a & b")) // bare & followed by a space
        assertEquals("hi&", convertAmpCodes("hi&")) // trailing &
    }
}
