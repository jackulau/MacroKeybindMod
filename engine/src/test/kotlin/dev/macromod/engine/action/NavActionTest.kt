package dev.macromod.engine.action

import dev.macromod.engine.ScriptHost
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
}
