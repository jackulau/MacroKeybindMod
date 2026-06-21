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
        // A real param INSIDE a brace block still substitutes: the `$$0` trigger is seen past the
        // `$${` delimiter, so the trigger-aware fast-path does NOT skip it.
        assertEquals("\$\${ say hi }\$\$", ParamSubstitutor(presets = listOf("hi")).process("\$\${ say \$\$0 }\$\$"))
    }

    @Test fun `process is allocation-free on a param-free modern-brace macro`() {
        val raw = ManagementFactory.getThreadMXBean()
        if (raw !is com.sun.management.ThreadMXBean || !raw.isThreadAllocatedMemorySupported) return // non-HotSpot: skip
        val tid = Thread.currentThread().id
        val sub = ParamSubstitutor()
        // The standard onTick form: a `$${ ... }$$` brace block whose ONLY `$$` are the delimiters.
        // contains("$$") is true, so the old coarse fast-path ran all five substitution Matchers
        // (~1048 B/call measured) every fire; the trigger-aware guard sees no param and returns the
        // source unchanged (0 B). This is the bread-and-butter automation path (fired up to 20x/s).
        val src = "\$\${ if(%HEALTH% < 10) { use() } log(\"low\") }\$\$"
        assertEquals(src, sub.process(src)) // must be a true no-op (identical output)
        var sink = 0
        repeat(200_000) { sink += sub.process(src).length } // warm up the JIT
        val iters = 1_000_000
        val before = raw.getThreadAllocatedBytes(tid)
        repeat(iters) { sink += sub.process(src).length }
        val perCall = (raw.getThreadAllocatedBytes(tid) - before).toDouble() / iters
        assertTrue(perCall < 64.0, "param-free brace macro process() should be ~0 B/call, was $perCall (sink=$sink)")
    }

    @Test fun `expand is allocation-free on a percent-without-variable line`() {
        val raw = ManagementFactory.getThreadMXBean()
        if (raw !is com.sun.management.ThreadMXBean || !raw.isThreadAllocatedMemorySupported) return // non-HotSpot: skip
        val tid = Thread.currentThread().id
        val expander = VariableExpander(dev.macromod.engine.variable.VariableRegistry())
        // The %var% sibling of the brace hole: a per-tick chat action like log("Progress: 50% done")
        // expands a string that holds bare `%` signs but no `%var%`. The old `indexOf('%') < 0` guard
        // let it run the Regex.replace Matcher (~208 B/call measured) for a no-op; hasVarRef requires a
        // real var-start char after the `%`, so it returns the text unchanged at 0 B. (Correctness of
        // the unchanged output + mixed literal-%/real-ref lines is pinned by ExpandTest.)
        val src = "Progress: 50% done, 20% to go (see http://x/y%20z)"
        assertEquals(src, expander.expand(src)) // must be a true no-op (identical output)
        var sink = 0
        repeat(200_000) { sink += expander.expand(src).length } // warm up the JIT
        val iters = 1_000_000
        val before = raw.getThreadAllocatedBytes(tid)
        repeat(iters) { sink += expander.expand(src).length }
        val perCall = (raw.getThreadAllocatedBytes(tid) - before).toDouble() / iters
        assertTrue(perCall < 64.0, "percent-without-variable expand() should be ~0 B/call, was $perCall (sink=$sink)")
    }
}
