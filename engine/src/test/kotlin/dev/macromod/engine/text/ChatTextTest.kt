package dev.macromod.engine.text

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test fun `plain text with no ampersand is returned unchanged`() {
        // Pins the convertAmpCodes fast-path: no `&` -> nothing to convert -> identical output. This is
        // the dominant per-tick output shape (a status line with no colour codes).
        assertEquals("Mining: 50 blocks done", convertAmpCodes("Mining: 50 blocks done"))
    }

    // --- parseChatSender: %CHATPLAYER% / %CHATMESSAGE% parity with MKB OnChatProvider.guessPlayer ---

    @Test fun `vanilla angle-bracket chat splits sender from message`() {
        // The dominant case: `<name> body`. Sender extracted, the `<name>` prefix stripped from the body.
        // This is also the negative-control anchor: without the prefix strip the message would be the
        // full line "<Steve> hello world" and this assertEquals fails.
        assertEquals(ChatSender("Steve", "hello world"), parseChatSender("<Steve> hello world"))
    }

    @Test fun `colon-style plugin chat splits sender from message`() {
        assertEquals(ChatSender("Steve", "hello"), parseChatSender("Steve: hello"))
    }

    @Test fun `bracketed token is treated as the sender`() {
        // No `<...>` and no leading `name:`, so the best-guess tier matches the `[Admin]` token.
        assertEquals(ChatSender("Admin", "hi there"), parseChatSender("[Admin] hi there"))
    }

    @Test fun `a line with no sender pattern yields an empty sender and the full message`() {
        assertEquals(ChatSender("", "Server is restarting in 5 minutes"),
            parseChatSender("Server is restarting in 5 minutes"))
    }

    @Test fun `sender matching is case-insensitive`() {
        // MKB compiled its patterns with CASE_INSENSITIVE, so an all-caps name still parses.
        assertEquals(ChatSender("STEVE", "yo"), parseChatSender("<STEVE> yo"))
    }

    @Test fun `a one-char angle name is not a vanilla sender`() {
        // MKB's name class is {2,16}, so `<a>` fails the vanilla tier; it then falls to the best-guess
        // tier where `<a>` has no 2+-char name token, so the raw guess is kept and the body is unchanged
        // (faithful to guessPlayer leaving the message untouched when no actual-name token is found).
        assertEquals(ChatSender("a", "<a> hi"), parseChatSender("<a> hi"))
    }

    @Test fun `an empty line yields empty sender and empty message`() {
        assertEquals(ChatSender("", ""), parseChatSender(""))
    }

    // --- allocation guards: the format converters are on the per-tick display path (log/title/toast/
    //     %CHATCLEAN%), so a code-free line must not run a wasted Matcher. See goal 102. ---

    @Test fun `convertAmpCodes is allocation-free on a line with no ampersand`() {
        val raw = ManagementFactory.getThreadMXBean()
        if (raw !is com.sun.management.ThreadMXBean || !raw.isThreadAllocatedMemorySupported) return // non-HotSpot: skip
        val tid = Thread.currentThread().id
        // log/title/toast all route display text through convertAmpCodes every fire; a status line with
        // no `&` used to run the AMP_CODE Matcher for a no-op (~184 B/call measured). The fast-path
        // returns it unchanged at 0 B. (Correctness of the unchanged output is pinned above.)
        val src = "Mining progress: 50 blocks done, 120 to go"
        assertEquals(src, convertAmpCodes(src)) // true no-op
        var sink = 0
        repeat(200_000) { sink += convertAmpCodes(src).length } // warm JIT
        val iters = 1_000_000
        val before = raw.getThreadAllocatedBytes(tid)
        repeat(iters) { sink += convertAmpCodes(src).length }
        val perCall = (raw.getThreadAllocatedBytes(tid) - before).toDouble() / iters
        assertTrue(perCall < 64.0, "no-ampersand convertAmpCodes should be ~0 B/call, was $perCall (sink=$sink)")
    }

    @Test fun `stripFormattingCodes is allocation-free on a line with no section sign`() {
        val raw = ManagementFactory.getThreadMXBean()
        if (raw !is com.sun.management.ThreadMXBean || !raw.isThreadAllocatedMemorySupported) return // non-HotSpot: skip
        val tid = Thread.currentThread().id
        // The `strip` action / %CHATCLEAN% on an already-clean line (no `§`) used to run the SECTION_CODE
        // Matcher for a no-op (~176 B/call measured); the fast-path returns it unchanged at 0 B.
        val src = "Mining progress: 50 blocks done, 120 to go"
        assertEquals(src, stripFormattingCodes(src)) // true no-op
        var sink = 0
        repeat(200_000) { sink += stripFormattingCodes(src).length } // warm JIT
        val iters = 1_000_000
        val before = raw.getThreadAllocatedBytes(tid)
        repeat(iters) { sink += stripFormattingCodes(src).length }
        val perCall = (raw.getThreadAllocatedBytes(tid) - before).toDouble() / iters
        assertTrue(perCall < 64.0, "no-section-sign stripFormattingCodes should be ~0 B/call, was $perCall (sink=$sink)")
    }
}
