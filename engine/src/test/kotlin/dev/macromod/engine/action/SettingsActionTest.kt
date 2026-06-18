package dev.macromod.engine.action

import dev.macromod.engine.ScriptHost
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsActionTest {
    private class RecSettings : ClientSettings {
        val calls = mutableListOf<Pair<String, List<String>>>()
        override fun apply(name: String, args: List<String>) { calls.add(name to args) }
    }
    private class Bridge(private val s: RecSettings) : ClientBridge {
        override val settings get() = s
    }

    @Test fun `settings actions route name + args to the platform`() {
        val s = RecSettings()
        ScriptHost().run(
            "\$\${ fov(70); gamma(100); volume(50); bind(\"jump\", 57); reloadresources }\$\$",
            client = Bridge(s),
        )
        assertEquals(
            listOf(
                "fov" to listOf("70"),
                "gamma" to listOf("100"),
                "volume" to listOf("50"),
                "bind" to listOf("jump", "57"),
                "reloadresources" to emptyList(),
            ),
            s.calls,
        )
    }
}
