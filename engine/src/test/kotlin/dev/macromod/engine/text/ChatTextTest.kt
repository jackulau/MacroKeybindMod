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
}
