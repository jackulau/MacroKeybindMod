package dev.macromod.engine

import dev.macromod.engine.runtime.ScriptException
import dev.macromod.engine.value.Value
import dev.macromod.engine.variable.IteratorBundle
import dev.macromod.engine.variable.VariableRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

    @Test fun `the bundle-var snapshot covers keys only later elements set (heterogeneous bundles)`() {
        // "het" element[0] sets only %FOO%; element[1] also sets %BAR%. The loop-exit snapshot must union
        // EVERY element's keys — snapshotting only element[0]'s {FOO} would leave %BAR% (set by element[1])
        // unrestored, leaking the inner loop's "hb1" into the outer body (which expects its own "ob").
        val reg = VariableRegistry().apply {
            addBundleProvider { name -> when (name) {
                "outer" -> listOf(IteratorBundle(Value.Str("O"),
                    mapOf("FOO" to Value.Str("oa"), "BAR" to Value.Str("ob"))))
                "het" -> listOf(
                    IteratorBundle(Value.Str("h0"), mapOf("FOO" to Value.Str("ha0"))),
                    IteratorBundle(Value.Str("h1"), mapOf("FOO" to Value.Str("ha1"), "BAR" to Value.Str("hb1"))),
                )
                else -> null
            } }
        }
        val out = runScript("foreach(&o, outer); foreach(&i, het); next; log(\"%FOO%|%BAR%\"); next", reg)
        assertEquals(listOf("oa|ob"), out.logs)
    }

    @Test fun `a nested foreach reusing the same loop var restores the outer loop var on inner exit`() {
        // Both loops use &x as the loop var (the loop-var analogue of the fixed-name leak 074 fixed).
        // After the inner loop finishes, the outer body must still see ITS current element. Without
        // loop-var scoping the inner clobbers &x and the outer logs the inner's trailing "q2" twice
        // instead of the outer's "p1"/"p2".
        val out = runScript(
            "push(&P[], \"p1\"); push(&P[], \"p2\"); push(&Q[], \"q1\"); push(&Q[], \"q2\"); " +
                "foreach(&x, &P[]); foreach(&x, &Q[]); next; log(\"%&x%\"); next"
        )
        assertEquals(listOf("p1", "p2"), out.logs)
    }

    @Test fun `foreach restores a pre-existing loop var to its value after the loop`() {
        // MKB supplies the loop var via the iterator provider it unregisters at loop close, so a
        // same-named user var reverts to its pre-loop value. &x holds "before"; without scoping it
        // persists the last element "p2".
        val out = runScript(
            "&x := \"before\"; push(&arr[], \"p1\"); push(&arr[], \"p2\"); foreach(&x, &arr[]); next; log(\"%&x%\")"
        )
        assertEquals(listOf("before"), out.logs)
    }

    @Test fun `foreach restores the pos var to its pre-loop value after the loop`() {
        // The offset var is iterator-supplied too (MKB ScriptedIteratorArray add(offsetVar, offset)), so
        // it reverts on loop exit. #p holds 99; without scoping it persists the last index 1.
        val out = runScript(
            "#p := 99; push(&arr[], \"p1\"); push(&arr[], \"p2\"); foreach(&v, &arr[], #p); next; log(\"%#p%\")"
        )
        assertEquals(listOf("99"), out.logs)
    }

    @Test fun `a stop inside a foreach restores the loop var instead of leaking it to the next run`() {
        // `stop` ends the macro with the foreach frame still open. MKB unregisters the iterator provider
        // in onStopped, so the loop var reverts. Without unwind-on-stop &x leaks the stopped element into
        // the SHARED registry, visible to the next macro: the 2nd run would log "p1" instead of "before".
        val reg = VariableRegistry()
        runScript("&x := \"before\"; push(&arr[], \"p1\"); push(&arr[], \"p2\"); foreach(&x, &arr[]); stop; next", reg)
        val out = runScript("log(\"%&x%\")", reg) // 2nd macro on the SAME shared registry
        assertEquals(listOf("before"), out.logs)
    }

    @Test fun `a crash inside a foreach restores the loop var instead of leaking it to the next run`() {
        // Like the `stop` case, but the macro CRASHES: the inner `do; loop` spins to the step limit while
        // the foreach frame is still open and &x = "p1". The host swallows the throw and drops the macro;
        // without unwind-on-error &x leaks "p1" into the SHARED registry, so the 2nd run logs "p1".
        val reg = VariableRegistry()
        assertFailsWith<ScriptException> {
            runScript("&x := \"before\"; push(&arr[], \"p1\"); foreach(&x, &arr[]); do; loop; next", reg)
        }
        val out = runScript("log(\"%&x%\")", reg) // 2nd macro on the SAME shared registry
        assertEquals(listOf("before"), out.logs)
    }
}
