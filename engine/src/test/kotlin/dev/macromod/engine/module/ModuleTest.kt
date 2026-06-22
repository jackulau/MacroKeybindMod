package dev.macromod.engine.module

import dev.macromod.engine.action.InputController
import dev.macromod.engine.action.OutputSink
import dev.macromod.engine.module.modules.AutoClicker
import dev.macromod.engine.module.modules.AutoReconnectModule
import dev.macromod.engine.module.modules.FailsafeModule
import dev.macromod.engine.module.modules.FarmModule
import dev.macromod.engine.module.modules.FishingModule
import dev.macromod.engine.module.modules.RowFarmModule
import dev.macromod.engine.value.Value
import dev.macromod.engine.variable.VariableRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModuleTest {
    private class RecInput : InputController {
        val taps = mutableListOf<String>()
        val held = mutableListOf<String>()
        val released = mutableListOf<String>()
        val looks = mutableListOf<Pair<Float, Float>>()
        override fun tap(key: String) { taps.add(key) }
        override fun hold(key: String) { held.add(key) }
        override fun release(key: String) { released.add(key) }
        override fun look(yaw: Float, pitch: Float) { looks.add(yaw to pitch) }
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

    @Test fun `a reused context with an advanced tick drives modules like a fresh one`() {
        // Mirrors MacroModClient's hot path: ONE ModuleContext whose `tick` is advanced each
        // tick (alloc-free) instead of a fresh context per tick. Must behave identically.
        val mgr = ManagerWith(AutoClicker(intervalTicks = 5))
        mgr.setEnabled("autoclicker", true)
        val input = RecInput()
        val ctx = ModuleContext(tick = 0, input = input)
        for (t in 0..10L) { ctx.tick = t; mgr.tick(ctx) }
        assertEquals(listOf("attack", "attack", "attack"), input.taps) // ticks 0, 5, 10 — same as fresh-per-tick
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

    @Test fun `disabling without a ctx still releases held keys via the last tick context`() {
        // The in-game GUI / keybind toggle calls toggle(name) with NO ctx. onDisable (which releases
        // held movement keys) must still run, or "forward" stays stuck down after the player turns the
        // farm OFF and they keep walking forward. The manager reuses the most recent tick() context.
        val mgr = ManagerWith(FarmModule())
        val input = RecInput()
        mgr.setEnabled("farm", true)
        mgr.tick(ctx(0, input)) // farm holds "forward"; manager remembers this ctx
        assertTrue("forward" in input.held)
        mgr.toggle("farm") // GUI-style disable: no ctx passed
        assertFalse(mgr.isEnabled("farm"))
        assertTrue("forward" in input.released, "onDisable must run via the remembered tick ctx")
    }

    private class RecOut : OutputSink {
        val logs = mutableListOf<String>()
        override fun chat(message: String) {}
        override fun log(message: String) { logs.add(message) }
    }

    @Test fun `failsafe disables guarded modules on low health`() {
        val mgr = ModuleManager()
        mgr.register(AutoClicker())
        mgr.register(FarmModule())
        mgr.register(FailsafeModule(mgr, listOf("autoclicker", "farm"), threshold = 6))
        mgr.setEnabled("autoclicker", true)
        mgr.setEnabled("farm", true)
        mgr.setEnabled("failsafe", true)
        val registry = VariableRegistry().apply { addEnvProvider { if (it == "HEALTH") Value.Num(3) else null } }
        val out = RecOut()
        mgr.tick(ModuleContext(tick = 0, input = RecInput(), output = out, registry = registry))
        assertFalse(mgr.isEnabled("autoclicker"))
        assertFalse(mgr.isEnabled("farm"))
        assertTrue(out.logs.any { it.contains("Failsafe") })
    }

    @Test fun `failsafe leaves modules enabled at safe health`() {
        val mgr = ModuleManager()
        mgr.register(AutoClicker())
        mgr.register(FailsafeModule(mgr, listOf("autoclicker"), threshold = 6))
        mgr.setEnabled("autoclicker", true)
        mgr.setEnabled("failsafe", true)
        val registry = VariableRegistry().apply { addEnvProvider { if (it == "HEALTH") Value.Num(20) else null } }
        mgr.tick(ModuleContext(tick = 0, input = RecInput(), output = OutputSink.NOOP, registry = registry))
        assertTrue(mgr.isEnabled("autoclicker"))
    }

    @Test fun `fishing casts, reels on bite, recasts after cooldown`() {
        val mgr = ModuleManager()
        mgr.register(FishingModule(recastDelay = 3))
        mgr.setEnabled("fishing", true)
        val input = RecInput()
        val reg = VariableRegistry()

        mgr.tick(ModuleContext(0, input, registry = reg)) // CAST → use
        assertEquals(listOf("use"), input.taps)
        mgr.tick(ModuleContext(1, input, registry = reg)) // WAITING, no bite
        assertEquals(listOf("use"), input.taps)

        reg.setVariable("FISHING_BITE", Value.Bool(true))
        mgr.tick(ModuleContext(2, input, registry = reg)) // bite → reel (use), cooldown until 5
        assertEquals(listOf("use", "use"), input.taps)

        reg.setVariable("FISHING_BITE", Value.Bool(false))
        mgr.tick(ModuleContext(3, input, registry = reg)) // COOLDOWN
        mgr.tick(ModuleContext(5, input, registry = reg)) // tick>=5 → back to CAST (no action yet)
        assertEquals(listOf("use", "use"), input.taps)
        mgr.tick(ModuleContext(6, input, registry = reg)) // CAST → use again
        assertEquals(listOf("use", "use", "use"), input.taps)
    }

    @Test fun `rowfarm walks then alternates a 180 turn at each row end`() {
        val mgr = ModuleManager()
        mgr.register(RowFarmModule())
        mgr.setEnabled("rowfarm", true)
        val input = RecInput()
        val reg = VariableRegistry()

        mgr.tick(ModuleContext(0, input, registry = reg)) // walking
        assertTrue("forward" in input.held)
        assertTrue("attack" in input.taps)

        reg.setVariable("AT_ROW_END", Value.Bool(true))
        mgr.tick(ModuleContext(1, input, registry = reg)) // turn 180
        reg.setVariable("AT_ROW_END", Value.Bool(false))
        mgr.tick(ModuleContext(2, input, registry = reg)) // walking back
        reg.setVariable("AT_ROW_END", Value.Bool(true))
        mgr.tick(ModuleContext(3, input, registry = reg)) // turn back to 0

        assertEquals(listOf(180f to 0f, 0f to 0f), input.looks)
    }

    @Test fun `autoreconnect is an off-by-default toggle carrying its config`() {
        val m = AutoReconnectModule(delayTicks = 40, maxAttempts = 3)
        assertEquals("autoreconnect", m.name)
        assertFalse(m.enabled)
        assertEquals(40, m.delayTicks)
        assertEquals(3, m.maxAttempts)
        val mgr = ManagerWith(m)
        mgr.toggle("autoreconnect")
        assertTrue(mgr.isEnabled("autoreconnect"))
        mgr.tick(ctx(0, RecInput())) // onTick is a harmless no-op (reconnect is host-driven)
        assertTrue(mgr.isEnabled("autoreconnect"))
    }

    private fun ManagerWith(module: Module): ModuleManager {
        val mgr = ModuleManager()
        mgr.register(module)
        return mgr
    }
}
