package dev.macromod.engine.macro

import dev.macromod.engine.ScriptHost
import dev.macromod.engine.action.ClientBridge
import dev.macromod.engine.action.InputController
import dev.macromod.engine.action.Navigator
import dev.macromod.engine.action.OutputSink
import dev.macromod.engine.variable.VariableRegistry

/**
 * Ties the whole platform-agnostic stack together: a [MacroRegistry] of bindings, a
 * [ScriptHost] to compile/run their scripts, and a shared [VariableRegistry] so state
 * (especially `@shared` variables) persists across firings.
 *
 * The Fabric layer owns the loop and input: it calls [fireKey] on a key press and
 * [fireEvent] each tick / on chat / on join, passing a real [OutputSink]. Everything
 * here is Minecraft-free and unit-testable.
 */
class MacroEngine(
    val host: ScriptHost = ScriptHost(),
    val macros: MacroRegistry = MacroRegistry(),
    val variables: VariableRegistry = VariableRegistry(),
    val input: InputController = InputController.NoOp,
    val navigator: Navigator = Navigator.NoOp,
    val client: ClientBridge = ClientBridge.NoOp,
) {
    /** Run every enabled macro bound to [keyCode]. */
    fun fireKey(keyCode: Int, output: OutputSink) {
        for (binding in macros.forKey(keyCode)) run(binding, output)
    }

    /** Run every enabled macro bound to the named event. */
    fun fireEvent(name: String, output: OutputSink) {
        for (binding in macros.forEvent(name)) run(binding, output)
    }

    private fun run(binding: MacroBinding, output: OutputSink) {
        host.compile(binding.script).run(host, output, variables, input, navigator, client)
    }
}
