package dev.macromod.engine.module.modules

import dev.macromod.engine.module.Module
import dev.macromod.engine.module.ModuleContext

/**
 * Taps a key every [intervalTicks] ticks while enabled — a generic auto-clicker
 * (`attack` for combat/breaking, `use` for placing/eating).
 */
class AutoClicker(
    val key: String = "attack",
    val intervalTicks: Int = 5,
) : Module {
    override val name = "autoclicker"
    override var enabled = false
    private var lastTick: Long? = null // null = never fired (avoids Long.MIN_VALUE subtraction overflow)

    override fun onTick(ctx: ModuleContext) {
        val last = lastTick
        if (last == null || ctx.tick - last >= intervalTicks) {
            ctx.input.tap(key)
            lastTick = ctx.tick
        }
    }
}

/**
 * Basic crop/forage farm helper: walk forward and continuously swing (or use). The
 * SkyBlock-specific parts (row turning, area bounds, pause-on-full-inventory) build on
 * top of this and the pathfinder; this is the always-moving core.
 */
class FarmModule(
    /** true → `use` (e.g. a hoe / cocoa), false → `attack` (e.g. wheat / sugar cane). */
    val useItem: Boolean = false,
) : Module {
    override val name = "farm"
    override var enabled = false

    override fun onTick(ctx: ModuleContext) {
        ctx.input.hold("forward")
        ctx.input.tap(if (useItem) "use" else "attack")
    }

    override fun onDisable(ctx: ModuleContext) {
        ctx.input.release("forward")
    }
}

/** Holds a movement key while enabled (e.g. auto-sprint, auto-walk); releases on disable. */
class KeyHolder(
    private val moduleName: String,
    private val key: String,
) : Module {
    override val name = moduleName
    override var enabled = false

    override fun onTick(ctx: ModuleContext) = ctx.input.hold(key)

    override fun onDisable(ctx: ModuleContext) = ctx.input.release(key)
}
