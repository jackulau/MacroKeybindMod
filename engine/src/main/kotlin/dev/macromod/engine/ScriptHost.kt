package dev.macromod.engine

import dev.macromod.engine.action.ActionRegistry
import dev.macromod.engine.action.ClientBridge
import dev.macromod.engine.action.InputController
import dev.macromod.engine.action.Navigator
import dev.macromod.engine.action.OutputSink
import dev.macromod.engine.action.ScriptAction
import dev.macromod.engine.action.builtin.CHAT_CRAFT_GUI_ACTIONS
import dev.macromod.engine.action.builtin.CONTROL_FLOW_ACTIONS
import dev.macromod.engine.action.builtin.CORE_ACTIONS
import dev.macromod.engine.action.builtin.INPUT_ACTIONS
import dev.macromod.engine.action.builtin.NAV_ACTIONS
import dev.macromod.engine.action.builtin.SETTINGS_ACTIONS
import dev.macromod.engine.action.builtin.STRING_MATH_ACTIONS
import dev.macromod.engine.action.builtin.TASK_CONFIG_ACTIONS
import dev.macromod.engine.action.builtin.WORLD_HUD_ACTIONS
import dev.macromod.engine.action.builtin.WORLD_QUERY_ACTIONS
import dev.macromod.engine.ast.Instruction
import dev.macromod.engine.param.ParamResolver
import dev.macromod.engine.param.ParamSubstitutor
import dev.macromod.engine.parser.ModernTranspiler
import dev.macromod.engine.parser.ScriptCompiler
import dev.macromod.engine.runtime.Interpreter
import dev.macromod.engine.runtime.RuntimeContext
import dev.macromod.engine.variable.VariableRegistry

/** An [ActionRegistry] preloaded with all built-in control-flow and core actions. */
fun defaultActionRegistry(): ActionRegistry {
    val registry = ActionRegistry()
    (CONTROL_FLOW_ACTIONS + CORE_ACTIONS + STRING_MATH_ACTIONS + INPUT_ACTIONS + NAV_ACTIONS + SETTINGS_ACTIONS + WORLD_HUD_ACTIONS + WORLD_QUERY_ACTIONS + TASK_CONFIG_ACTIONS + CHAT_CRAFT_GUI_ACTIONS).forEach { registry.register(it) }
    return registry
}

/**
 * Top-level façade: holds the action registry + param configuration, compiles source
 * to a [MacroScript], and runs it. The Fabric host extends this with MC-bound actions,
 * a real [OutputSink], a [ParamResolver] for prompts, and environment variable providers.
 */
class ScriptHost(
    val actions: ActionRegistry = defaultActionRegistry(),
    paramResolver: ParamResolver = ParamResolver.NONE,
    presets: List<String> = emptyList(),
) {
    private val compiler = ScriptCompiler(actions)
    private val params = ParamSubstitutor(paramResolver, presets)
    private val modern = ModernTranspiler()

    /** Register an extra action (e.g. an MC-bound or module action). */
    fun register(action: ScriptAction): Boolean = actions.register(action)

    /** Compile the bind format (chat text + `$${ … }$$` script islands). */
    fun compile(source: String): MacroScript = MacroScript(compiler.compileMacro(params.process(source)))

    /** Compile a pure script body (`.txt` file — every statement is script). */
    fun compileScript(body: String): MacroScript = MacroScript(compiler.compileScript(params.process(body)))

    /** Compile the modern brace-block syntax (transpiled to the legacy form, then compiled). */
    fun compileModern(source: String): MacroScript = compileScript(modern.transpile(source))

    /** Convenience: compile + run [source], returning the (possibly pre-seeded) variable registry. */
    fun run(
        source: String,
        output: OutputSink = OutputSink.NOOP,
        registry: VariableRegistry = VariableRegistry(),
        input: InputController = InputController.NoOp,
        navigator: Navigator = Navigator.NoOp,
        client: ClientBridge = ClientBridge.NoOp,
    ): VariableRegistry {
        compile(source).run(this, output, registry, input, navigator, client)
        return registry
    }
}

/** A compiled macro: a flat instruction list ready to execute against any context. */
class MacroScript(val program: List<Instruction>) {
    val size: Int get() = program.size

    fun run(
        host: ScriptHost,
        output: OutputSink = OutputSink.NOOP,
        registry: VariableRegistry = VariableRegistry(),
        input: InputController = InputController.NoOp,
        navigator: Navigator = Navigator.NoOp,
        client: ClientBridge = ClientBridge.NoOp,
    ) {
        // Loop the resumable interpreter to completion. A wait-free script finishes in one call;
        // `wait` suspends and returns ticks — the synchronous run() API resumes immediately (a
        // tick-paced host honours the delay between resumes instead).
        val interp = Interpreter(program, RuntimeContext(registry, output, input, navigator, client))
        @Suppress("ControlFlowWithEmptyBody")
        while (interp.run() >= 0) { }
    }
}
