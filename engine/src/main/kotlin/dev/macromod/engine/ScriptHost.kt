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

    /**
     * Compiled-program cache for the bind format. A bound macro (onTick, a held keybind, …) was
     * recompiled from source on EVERY fire — up to 20x/second for an onTick — which is the
     * dominant hot-path cost (full parse + per-statement regex + a fresh instruction list each
     * time). The compiled program is immutable (the interpreter holds its own mutable pointer +
     * stack), so it is safe to reuse across fires. Keying by the POST-substitution text keeps it
     * correct for fire-varying `$$` param codes (`$$?`, `$$i`, …): those yield a different key per
     * fire and recompile, while the common param-free script hits the cache every time. Bounded
     * LRU so a script whose params vary widely can't grow it without limit. Single-threaded engine,
     * so no synchronization needed.
     */
    private val programCache = object : LinkedHashMap<String, MacroScript>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, MacroScript>): Boolean = size > MAX_PROGRAM_CACHE
    }

    /** Register an extra action (e.g. an MC-bound or module action). */
    fun register(action: ScriptAction): Boolean {
        val added = actions.register(action)
        if (added) programCache.clear() // a new keyword can change how cached sources compile
        return added
    }

    /** Compile the bind format (chat text + `$${ … }$$` script islands). */
    fun compile(source: String): MacroScript {
        val processed = params.process(source)
        // Cache the immutable MacroScript wrapper itself, not just its inner program, so a cache hit
        // returns the shared instance with zero allocation. MacroScript holds only the program and
        // run() builds a fresh Interpreter per fire, so sharing one instance across fires is safe.
        // Re-wrapping the cached program in a new MacroScript every fire was the last ~13 B/call on
        // the compile hot path (after goal 099 fast-pathed param-process for the brace syntax).
        return programCache.getOrPut(processed) { MacroScript(compiler.compileMacro(processed)) }
    }

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

    private companion object {
        /** Cap on distinct compiled programs retained (LRU-evicted beyond this). */
        const val MAX_PROGRAM_CACHE = 256
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
        registry.clearLatched() // fresh `%~NAME%` snapshots for this run
        val interp = Interpreter(program, RuntimeContext(registry, output, input, navigator, client))
        @Suppress("ControlFlowWithEmptyBody")
        while (interp.run() >= 0) { }
    }
}
