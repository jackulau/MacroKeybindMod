package dev.macromod.engine

import dev.macromod.engine.action.ClientBridge
import dev.macromod.engine.action.ConfigController
import dev.macromod.engine.variable.VariableRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

/** D1: the `config` action routes the profile name to the platform ConfigController. */
class ConfigActionTest {
    @Test fun `config action switches the active profile via the controller`() {
        val switched = mutableListOf<String>()
        val bridge = object : ClientBridge {
            override val config = object : ConfigController {
                override fun switchConfig(name: String) { switched.add(name) }
            }
        }
        ScriptHost().run(
            "\$\${ config(\"skyblock\") }\$\$",
            RecordingOutput(), VariableRegistry(), client = bridge,
        )
        assertEquals(listOf("skyblock"), switched)
    }
}
