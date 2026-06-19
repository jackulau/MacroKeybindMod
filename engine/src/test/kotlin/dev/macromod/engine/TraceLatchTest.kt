package dev.macromod.engine

import dev.macromod.engine.action.ClientBridge
import dev.macromod.engine.action.WorldQuery
import dev.macromod.engine.value.Value
import dev.macromod.engine.variable.VariableRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * D8: the `trace` action populates the `%TRACE*%` local vars from the platform's detailed result,
 * and `%~NAME%` latches a value at first access (the engine-side "captured at script start" feature).
 */
class TraceLatchTest {

    @Test fun `trace action populates the TRACE star vars from the platform`() {
        val bridge = object : ClientBridge {
            override val query = object : WorldQuery {
                override fun trace(distance: Int) = "minecraft:stone"
                override fun traceVars(distance: Int) = linkedMapOf(
                    "TRACETYPE" to "block",
                    "TRACEID" to "minecraft:stone",
                    "TRACEX" to "10",
                    "TRACESIDE" to "T",
                )
            }
        }
        val out = RecordingOutput()
        ScriptHost().run(
            "\$\${ trace(); log(\"%TRACETYPE% %TRACEID% %TRACEX% %TRACESIDE%\") }\$\$",
            out, VariableRegistry(), client = bridge,
        )
        assertEquals(listOf("block minecraft:stone 10 T"), out.logs)
    }

    @Test fun `latched tilde var captures on first access and survives later changes`() {
        var live = 5
        val reg = VariableRegistry()
        reg.addEnvProvider { n -> if (n == "X") Value.Num(live) else null }
        reg.clearLatched()
        assertEquals(Value.Num(5), reg.getVariable("~X")) // captured at first access
        live = 99
        assertEquals(Value.Num(99), reg.getVariable("X"))  // live value reflects the change
        assertEquals(Value.Num(5), reg.getVariable("~X"))  // latched snapshot unchanged
        reg.clearLatched()
        assertEquals(Value.Num(99), reg.getVariable("~X")) // re-latched after a new run
    }
}
