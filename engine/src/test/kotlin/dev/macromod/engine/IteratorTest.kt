package dev.macromod.engine

import dev.macromod.engine.value.Value
import dev.macromod.engine.variable.IteratorBundle
import dev.macromod.engine.variable.VariableRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

class IteratorTest {

    @Test fun `foreach env iterates the set scalar variables`() {
        // two scalars are set, so the env iterator yields two values -> two iterations
        val out = runScript("#a := 1; &b := \"y\"; foreach(&v, env); log(\"got\"); next")
        assertEquals(2, out.logs.size)
    }

    @Test fun `foreach running iterates the (empty engine-side) task list`() {
        val out = runScript("foreach(&v, running); log(\"got\"); next")
        assertEquals(0, out.logs.size)
    }

    @Test fun `foreach still iterates an array`() {
        val out = runScript("push(&arr[], \"p\"); push(&arr[], \"q\"); foreach(&v, &arr[]); log(\"%&v%\"); next")
        assertEquals(listOf("p", "q"), out.logs)
    }

    @Test fun `foreach iterates a host-supplied iterator provider`() {
        // the host wires named iterators (players / hotbar / inventory) through this hook
        val reg = VariableRegistry().apply {
            addIteratorProvider { name ->
                if (name == "colors") listOf(Value.Str("red"), Value.Str("green"), Value.Str("blue")) else null
            }
        }
        val out = runScript("foreach(&v, colors); log(\"%&v%\"); next", reg)
        assertEquals(listOf("red", "green", "blue"), out.logs)
    }

    @Test fun `an unknown iterator with no provider yields no iterations`() {
        val out = runScript("foreach(&v, nope); log(\"x\"); next")
        assertEquals(0, out.logs.size)
    }

    @Test fun `foreach iterates a multi-var bundle provider exposing fixed-name vars`() {
        // The loop var binds to each bundle's primary; the fixed-name vars (EFFECTNAME / EFFECTTIME)
        // resolve in-body via the transient map — the shape the host effects iterator will use.
        val reg = VariableRegistry().apply {
            addBundleProvider { name ->
                if (name == "fx") listOf(
                    IteratorBundle(Value.Str("poison"), mapOf("EFFECTNAME" to Value.Str("poison"), "EFFECTTIME" to Value.Num(30))),
                    IteratorBundle(Value.Str("speed"), mapOf("EFFECTNAME" to Value.Str("speed"), "EFFECTTIME" to Value.Num(10))),
                ) else null
            }
        }
        val out = runScript("foreach(&e, fx); log(\"%&e%|%EFFECTNAME%|%EFFECTTIME%\"); next", reg)
        assertEquals(listOf("poison|poison|30", "speed|speed|10"), out.logs)
    }

    @Test fun `an empty bundle iterator yields no iterations`() {
        val reg = VariableRegistry().apply { addBundleProvider { name -> if (name == "fx") emptyList() else null } }
        val out = runScript("foreach(&e, fx); log(\"x\"); next", reg)
        assertEquals(0, out.logs.size)
    }

    @Test fun `a nested foreach over the same bundle iterator restores the outer's fixed-name vars`() {
        // Both loops over "fx" set the SAME transient %EFFECTNAME%. After the inner loop finishes, the
        // outer body must still see ITS current element's name, not the inner loop's last value. Without
        // loop-scoped restore this logs "poison|speed" then "speed|speed" (the inner's trailing leak).
        val reg = VariableRegistry().apply {
            addBundleProvider { name ->
                if (name == "fx") listOf(
                    IteratorBundle(Value.Str("poison"), mapOf("EFFECTNAME" to Value.Str("poison"))),
                    IteratorBundle(Value.Str("speed"), mapOf("EFFECTNAME" to Value.Str("speed"))),
                ) else null
            }
        }
        val out = runScript("foreach(&a, fx); foreach(&b, fx); next; log(\"%&a%|%EFFECTNAME%\"); next", reg)
        assertEquals(listOf("poison|poison", "speed|speed"), out.logs)
    }
}
