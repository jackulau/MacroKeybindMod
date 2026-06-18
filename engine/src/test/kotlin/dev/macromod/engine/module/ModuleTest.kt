package dev.macromod.engine.module

import dev.macromod.engine.action.InputController
import dev.macromod.engine.module.modules.AutoClicker
import dev.macromod.engine.module.modules.FarmModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModuleTest {
    private class RecInput : InputController {
        val taps = mutableListOf<String>()
        val held = mutableListOf<String>()
        val released = mutableListOf<String>()
        override fun tap(key: String) { taps.add(key) }
        override fun hold(key: String) { held.add(key) }
        override fun release(key: String) { released.add(key) }
        override fun look(yaw: Float, pitch: Float) {}
        override fun turn(deltaYaw: Float, deltaPitch: Float) {}
    }

    private fun ctx(tick: Long, input: InputController) = ModuleContext(tick = tick, input = input)

    @Test fun `autoclicker taps at its interval`() {
        val mgr = ManagerWith(AutoClicker(intervalTicks = 5))
        mgr.setEnabled("autoclicker", true)
        val input = RecInput()
        for (t in 0..10L) mgr.tick(ctx(t, input))
        assertEquals(listOf("attack", "attack", "attack"), input.taps) // ticks 0, 5, 10
    }

    @Test fun `only enabled modules tick`() {
        val mgr = ManagerWith(AutoClicker(intervalTicks = 1))
        val input = RecInput()
        mgr.tick(ctx(0, input)) // disabled by default
        assertTrue(input.taps.isEmpty())
        mgr.setEnabled("autoclicker", true)
        mgr.tick(ctx(1, input))
        assertEquals(listOf("attack"), input.taps)
    }

    @Test fun `toggle flips enabled state`() {
        val mgr = ManagerWith(AutoClicker())
        assertFalse(mgr.isEnabled("autoclicker"))
        mgr.toggle("autoclicker")
        assertTrue(mgr.isEnabled("autoclicker"))
        mgr.toggle("autoclicker")
        assertFalse(mgr.isEnabled("autoclicker"))
    }

    @Test fun `farm holds forward and swings, releases on disable`() {
        val mgr = ManagerWith(FarmModule())
        mgr.setEnabled("farm", true)
        val input = RecInput()
        mgr.tick(ctx(0, input))
        assertTrue("forward" in input.held)
        assertTrue("attack" in input.taps)
        mgr.setEnabled("farm", false, ctx(1, input))
        assertTrue("forward" in input.released)
    }

    private fun ManagerWith(module: Module): ModuleManager {
        val mgr = ModuleManager()
        mgr.register(module)
        return mgr
    }
}
