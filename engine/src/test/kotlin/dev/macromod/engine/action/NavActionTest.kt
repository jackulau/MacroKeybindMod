package dev.macromod.engine.action

import dev.macromod.engine.ScriptHost
import dev.macromod.engine.value.Value
import dev.macromod.engine.variable.VariableRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NavActionTest {
    private class FakeNavigator(private val found: Boolean = true) : Navigator {
        val targets = mutableListOf<Triple<Int, Int, Int>>()
        var stopped = false
        override fun pathTo(x: Int, y: Int, z: Int): Boolean {
            targets.add(Triple(x, y, z))
            return found
        }
        override fun stop() { stopped = true }
        override val isNavigating: Boolean get() = false
    }

    @Test fun `goto navigates to a block position`() {
        val nav = FakeNavigator()
        ScriptHost().run("\$\${ goto(10, 64, -5) }\$\$", navigator = nav)
        assertEquals(listOf(Triple(10, 64, -5)), nav.targets)
    }

    @Test fun `goto evaluates expression arguments`() {
        val nav = FakeNavigator()
        ScriptHost().run("\$\${ #x := 5; goto(#x * 2, 64, 0) }\$\$", navigator = nav)
        assertEquals(listOf(Triple(10, 64, 0)), nav.targets)
    }

    @Test fun `goto result is capturable`() {
        val nav = FakeNavigator(found = true)
        val registry = VariableRegistry()
        ScriptHost().run("\$\${ ok = goto(1, 2, 3) }\$\$", registry = registry, navigator = nav)
        assertTrue(registry.getVariable("ok")!!.asBoolean())
    }

    @Test fun `stopnav cancels navigation`() {
        val nav = FakeNavigator()
        ScriptHost().run("\$\${ stopnav }\$\$", navigator = nav)
        assertTrue(nav.stopped)
    }

    /** A registry whose env supplies the player position calcyawto reads. */
    private fun posRegistry(x: Int, z: Int) = VariableRegistry().apply {
        addEnvProvider { name -> when (name) { "XPOS" -> Value.Num(x); "ZPOS" -> Value.Num(z); else -> null } }
    }

    @Test fun `calcyawto writes yaw and distance to its out-vars`() {
        val reg = posRegistry(x = 10, z = 0)
        // target column (10,10): dx=0, dz=10 -> faces +Z (yaw 0), distance 10
        ScriptHost().run("\$\${ calcyawto(10, 10, #yaw, #dist) }\$\$", registry = reg)
        assertEquals(0, reg.getVariable("#yaw")!!.asInt())
        assertEquals(10, reg.getVariable("#dist")!!.asInt())
    }

    @Test fun `calcyawto yaw is -90 for a +X target and is capturable`() {
        val reg = posRegistry(x = 0, z = 0)
        // target (10,0): dx=10, dz=0 -> atan2(-10,0) = -90 degrees
        ScriptHost().run("\$\${ #y = calcyawto(10, 0) }\$\$", registry = reg)
        assertEquals(-90, reg.getVariable("#y")!!.asInt())
    }
}
