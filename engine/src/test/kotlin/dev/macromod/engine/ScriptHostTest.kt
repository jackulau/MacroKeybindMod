package dev.macromod.engine

import dev.macromod.engine.variable.VariableRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class ScriptHostTest {
    @Test fun `compile reuses the cached program for an identical source`() {
        val host = ScriptHost()
        val a = host.compile("\$\${ log(\"hi\") }\$\$")
        val b = host.compile("\$\${ log(\"hi\") }\$\$")
        assertSame(a.program, b.program) // cache hit: not recompiled
        val c = host.compile("\$\${ log(\"bye\") }\$\$")
        assertNotSame(a.program, c.program) // distinct source -> distinct program
    }

    @Test fun `a cached program runs correctly across repeated fires`() {
        // The shared (immutable) program must not carry interpreter state between runs: each
        // fire gets a fresh Interpreter, so a counter advances normally on the second fire.
        val host = ScriptHost()
        val script = "\$\${ inc(#n); log(\"%#n%\") }\$\$"
        val r = VariableRegistry()
        host.compile(script).run(host, registry = r) // #n: 0 -> 1
        host.compile(script).run(host, registry = r) // reused program, fresh interp: 1 -> 2
        assertEquals(2, r.getVariable("#n")!!.asInt())
    }

    @Test fun `compile cache evicts oldest entries when it grows past 256`() {
        // programCache is a LinkedHashMap with removeEldestEntry: size > 256. Compile 257 distinct
        // sources — the first one must be evicted and recompiled on re-access, proving the cap is
        // active (without this, the cache grows unbounded in a long session with many macros).
        val host = ScriptHost()
        val first = "\$\${ log(\"script_0\") }\$\$"
        val firstProgram = host.compile(first).program
        // Fill the cache past the cap (entry 0 + 256 more = 257 entries -> entry 0 is evicted).
        for (i in 1..256) host.compile("\$\${ log(\"script_$i\") }\$\$")
        // Recompiling the first source must give a NEW program object (evicted -> recompiled).
        val refetched = host.compile(first).program
        assertNotSame(firstProgram, refetched)
    }

    @Test fun `registering an action invalidates the cache`() {
        val host = ScriptHost()
        val before = host.compile("\$\${ log(\"x\") }\$\$")
        // Registering any action clears the cache so a newly-known keyword can't be shadowed by a
        // stale compile. The action body is irrelevant (default no-op execute).
        host.register(object : dev.macromod.engine.action.ScriptAction("zzznoop") {})
        val after = host.compile("\$\${ log(\"x\") }\$\$")
        assertNotSame(before.program, after.program) // recompiled after invalidation
    }
}
