package dev.macromod.engine

import dev.macromod.engine.action.ActionRegistry
import dev.macromod.engine.action.OutputSink
import dev.macromod.engine.action.ScriptAction
import dev.macromod.engine.action.builtin.CONTROL_FLOW_ACTIONS
import dev.macromod.engine.action.builtin.CORE_ACTIONS
import dev.macromod.engine.ast.Instruction
import dev.macromod.engine.param.ParamResolver
import dev.macromod.engine.param.ParamSubstitutor
import dev.macromod.engine.parser.ScriptCompiler
import dev.macromod.engine.runtime.Interpreter
import dev.macromod.engine.runtime.RuntimeContext
import dev.macromod.engine.variable.VariableRegistry

/** An [ActionRegistry] preloaded with all built-in control-flow and core actions. */
fun defaultActionRegistry(): ActionRegistry {
    val registry = ActionRegistry()
    (CONTROL_FLOW_ACTIONS + CORE_ACTIONS).forEach { registry.register(it) }
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

    /** Register an extra action (e.g. an MC-bound or module action). */
    fun register(action: ScriptAction): Boolean = actions.register(action)

    /** Compile the bind format (chat text + `$${ … }$$` script islands). */
    fun compile(source: String): MacroScript = MacroScript(compiler.compileMacro(params.process(source)))

    /** Compile a pure script body (`.txt` file / modern layer — every statement is script). */
    fun compileScript(body: String): MacroScript = MacroScript(compiler.compileScript(params.process(body)))

    /** Convenience: compile + run [source], returning the (possibly pre-seeded) variable registry. */
    fun run(
        source: String,
        output: OutputSink = OutputSink.NOOP,
        registry: VariableRegistry = VariableRegistry(),
    ): VariableRegistry {
        compile(source).run(this, output, registry)
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
    ) {
        Interpreter(program, RuntimeContext(registry, output)).run()
    }
}
