package dev.macromod.engine.module.modules

import dev.macromod.engine.module.Module
import dev.macromod.engine.module.ModuleContext

/**
 * Auto-fishing state machine: cast → wait for a bite → reel → recast after a short delay.
 *
 * The bite signal is read from a variable ([biteVar]) in the shared engine variable registry,
 * set by whoever detects a hook: a user macro (e.g. an `onSound` handler on the bobber splash)
 * or a built-in host detector. NOTE: no built-in detector is wired in the Fabric host yet, so out
 * of the box this module needs a script to supply [biteVar]; the state machine here (timing +
 * recast logic) is complete and unit-tested — it consumes the signal rather than producing it.
 */
class FishingModule(
    private val biteVar: String = "FISHING_BITE",
    private val recastDelay: Int = 10,
) : Module {
    override val name = "fishing"
    override var enabled = false

    private enum class State { CAST, WAITING, COOLDOWN }
    private var state = State.CAST
    private var cooldownUntil = 0L

    override fun onTick(ctx: ModuleContext) {
        when (state) {
            State.CAST -> {
                ctx.input.tap("use") // cast the rod
                state = State.WAITING
            }
            State.WAITING -> {
                val biting = ctx.registry.getVariable(biteVar)?.asBoolean() ?: false
                if (biting) {
                    ctx.input.tap("use") // reel in
                    cooldownUntil = ctx.tick + recastDelay
                    state = State.COOLDOWN
                }
            }
            State.COOLDOWN -> {
                if (ctx.tick >= cooldownUntil) state = State.CAST
            }
        }
    }

    override fun onDisable(ctx: ModuleContext) {
        state = State.CAST
    }
}

/**
 * Row-based crop farm: walk forward and swing; when [atRowEndVar] is set (a wall/edge ahead),
 * turn 180° and continue down the next row, alternating direction.
 *
 * [atRowEndVar] is read from the shared engine variable registry; the "am I at a row end?"
 * detection that sets it (raycast/collision) is supplied by whoever drives the module — a user
 * macro or a built-in host detector. NOTE: no built-in detector is wired in the Fabric host yet,
 * so without a script supplying [atRowEndVar] this degrades to plain walk-and-swing (it never
 * turns). The walk/turn/alternate logic here is engine-side and unit-tested.
 */
class RowFarmModule(
    private val atRowEndVar: String = "AT_ROW_END",
    private val swing: Boolean = true,
) : Module {
    override val name = "rowfarm"
    override var enabled = false

    private var headingForward = true // false after an odd number of turns
    private var turning = false

    override fun onTick(ctx: ModuleContext) {
        val atEnd = ctx.registry.getVariable(atRowEndVar)?.asBoolean() ?: false
        if (atEnd) {
            if (!turning) {
                headingForward = !headingForward
                ctx.input.look(if (headingForward) 0f else 180f, 0f)
                turning = true
            }
            return // don't keep walking into the wall while turning
        }
        turning = false
        ctx.input.hold("forward")
        if (swing) ctx.input.tap("attack")
    }

    override fun onDisable(ctx: ModuleContext) {
        ctx.input.release("forward")
    }
}
