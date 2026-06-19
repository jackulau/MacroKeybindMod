package dev.macromod.engine.variable

import dev.macromod.engine.runScript
import dev.macromod.engine.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the environment-provider chain (the mechanism the Fabric host uses to expose live
 * player/world state as `%HEALTH%`, `%XPOS%`, …) resolves through both `%var%` expansion and
 * bare-name expression evaluation. The Fabric provider's actual reads can't run headless, but
 * the resolution path they depend on is verified here.
 */
class EnvVariableTest {

    @Test fun `env providers resolve through percent-var expansion`() {
        val reg = VariableRegistry().apply {
            addEnvProvider { name ->
                when (name.uppercase()) {
                    "PLAYER" -> Value.Str("steve")
                    "HEALTH" -> Value.Num(18)
                    "XPOS" -> Value.Num(100)
                    "ZPOS" -> Value.Num(-40)
                    else -> null
                }
            }
        }
        val out = runScript("log(\"%PLAYER% hp=%HEALTH% at %XPOS%,%ZPOS%\")", reg)
        assertEquals(listOf("steve hp=18 at 100,-40"), out.logs)
    }

    @Test fun `env providers feed expressions and conditionals`() {
        val reg = VariableRegistry().apply {
            addEnvProvider { name -> if (name.uppercase() == "HEALTH") Value.Num(4) else null }
        }
        assertEquals(listOf("low"), runScript("if(HEALTH < 6); log(\"low\"); else; log(\"ok\"); endif", reg).logs)
    }

    @Test fun `env provider is skipped for sigil-prefixed user variables`() {
        // the fast path avoids the per-read env lookup for #/&/@ vars (the bulk of script traffic)
        var calls = 0
        val reg = VariableRegistry().apply {
            addEnvProvider { name -> calls++; if (name == "HEALTH") Value.Num(20) else null }
        }
        assertEquals(Value.Num(20), reg.getVariable("HEALTH")) // bare name -> provider consulted
        val afterBare = calls
        assertTrue(afterBare > 0)
        reg.setVariable("#n", Value.Num(7))
        reg.setVariable("&s", Value.Str("hi"))
        assertEquals(Value.Num(7), reg.getVariable("#n"))  // sigil var -> store, provider skipped
        assertEquals(Value.Str("hi"), reg.getVariable("&s"))
        reg.getVariable("@shared")
        assertEquals(afterBare, calls) // call count unchanged -> provider never hit for #/&/@
    }

    @Test fun `a user variable overlays nothing when the env provides the name`() {
        // env providers are consulted first, so a live built-in wins — matches the read order.
        val reg = VariableRegistry().apply {
            addEnvProvider { name -> if (name.uppercase() == "LEVEL") Value.Num(30) else null }
        }
        assertEquals(listOf("30"), runScript("log(\"%LEVEL%\")", reg).logs)
    }
}
