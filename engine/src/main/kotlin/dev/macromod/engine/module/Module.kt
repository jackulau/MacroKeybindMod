package dev.macromod.engine.module

import dev.macromod.engine.action.InputController
import dev.macromod.engine.action.Navigator
import dev.macromod.engine.action.OutputSink
import dev.macromod.engine.variable.VariableRegistry

/**
 * A toggleable automation built on the engine's platform primitives (input, navigation,
 * output, variables). Modules are the SkyBlock-style "macros that run every tick" — their
 * decision logic is platform-agnostic and unit-testable; the Fabric layer just ticks the
 * [ModuleManager] each client tick with a real [ModuleContext].
 */
interface Module {
    val name: String
    var enabled: Boolean

    /** Called once per tick while [enabled]. */
    fun onTick(ctx: ModuleContext)

    /** Cleanup when disabled (e.g. release held keys). */
    fun onDisable(ctx: ModuleContext) {}
}

/**
 * Everything a module sees on a tick. [tick] is a monotonically increasing tick counter.
 * It is a `var` so the host can advance one reused context per tick instead of allocating a
 * fresh [ModuleContext] every client tick — modules only read it (copying the value into their
 * own state, e.g. `lastTick = ctx.tick`), never retaining the context reference across ticks.
 */
class ModuleContext(
    var tick: Long,
    val input: InputController,
    val output: OutputSink = OutputSink.NOOP,
    val navigator: Navigator = Navigator.NoOp,
    val registry: VariableRegistry = VariableRegistry(),
)

/** Registers modules and ticks the enabled ones. */
class ModuleManager {
    private val modules = LinkedHashMap<String, Module>()

    /**
     * The most recent context handed to [tick]. Reused as the cleanup context when a module is
     * disabled WITHOUT an explicit one (the in-game GUI / keybind toggle calls [toggle]/[setEnabled]
     * with `ctx = null`): without it [Module.onDisable] is skipped, so a module that holds a key
     * (Farm/RowFarm release "forward" on disable) would leave it stuck down — the player keeps
     * walking forward after turning the module off. Null only before the first tick, when no module
     * has run [Module.onTick] yet and so holds nothing to release.
     */
    private var lastTickCtx: ModuleContext? = null

    fun register(module: Module): Module {
        modules[module.name.lowercase()] = module
        return module
    }

    fun get(name: String): Module? = modules[name.lowercase()]

    fun all(): List<Module> = modules.values.toList()

    fun isEnabled(name: String): Boolean = get(name)?.enabled ?: false

    fun setEnabled(name: String, enabled: Boolean, ctx: ModuleContext? = null) {
        val module = get(name) ?: return
        if (module.enabled == enabled) return
        module.enabled = enabled
        // Run cleanup on the caller's context, else fall back to the last tick's (the GUI/keybind
        // toggle passes none) so held keys are always released.
        if (!enabled) (ctx ?: lastTickCtx)?.let(module::onDisable)
    }

    fun toggle(name: String, ctx: ModuleContext? = null) {
        get(name)?.let { setEnabled(name, !it.enabled, ctx) }
    }

    /** Tick every enabled module. The `modules.values` iterator is non-escaping → JIT-scalarized (zero per-tick alloc, measured). */
    fun tick(ctx: ModuleContext) {
        lastTickCtx = ctx // remembered so a later no-ctx disable (GUI/keybind) can still run onDisable
        for (module in modules.values) if (module.enabled) module.onTick(ctx)
    }
}
