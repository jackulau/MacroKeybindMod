package dev.macromod.engine.param

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Allocation regression guard for the compile-time `$$` substitution fast-path.
 *
 * `ScriptHost.compile()` runs `params.process()` on every fire — including programCache HITS, since
 * the cache is keyed on the post-substitution text so `process()` must run to compute the key. A
 * param-free onTick macro fires up to 20x/second, so that path must not allocate: before the
 * fast-path it cost ~728 B/call (five escaping `Matcher`s that HotSpot can't scalar-replace because
 * the replace lambda captures the `MatchResult`). The fast-path returns the source unchanged when it
 * holds no `$$` and no `\`, dropping it to 0. Mirrors PathfindBenchmarkTest's measurement pattern.
 */
class ParamProcessAllocTest {
    @Test fun `process is allocation-free on a param-free source`() {
        val raw = ManagementFactory.getThreadMXBean()
        if (raw !is com.sun.management.ThreadMXBean || !raw.isThreadAllocatedMemorySupported) return // non-HotSpot: skip
        val tid = Thread.currentThread().id
        val sub = ParamSubstitutor()
        val src = "say hello world this is a plain onTick macro with no params at all"
        assertEquals(src, sub.process(src)) // the fast-path must be a true no-op (identical output)
        var sink = 0
        repeat(200_000) { sink += sub.process(src).length } // warm up the JIT
        val iters = 1_000_000
        val before = raw.getThreadAllocatedBytes(tid)
        repeat(iters) { sink += sub.process(src).length }
        val perCall = (raw.getThreadAllocatedBytes(tid) - before).toDouble() / iters
        assertTrue(perCall < 64.0, "param-free process() should be ~0 B/call, was $perCall (sink=$sink)")
    }

    @Test fun `process still substitutes when a param or escape is present`() {
        // The fast-path must NOT short-circuit a real `$$` source or a `\` escape.
        assertEquals("say hi", ParamSubstitutor(presets = listOf("hi")).process("say \$\$0"))
        assertEquals("keep ", ParamSubstitutor().process("keep \$\$! drop"))
        assertEquals("\$\$0", ParamSubstitutor(presets = listOf("X")).process("\\\$\$0")) // `\` runs unescape
    }
}
