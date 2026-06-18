package dev.macromod.engine.module.modules

import dev.macromod.engine.module.Module
import dev.macromod.engine.module.ModuleContext

/**
 * Auto-fishing state machine: cast → wait for a bite → reel → recast after a short delay.
 *
 * The bite signal is read from a variable ([biteVar]) that the Fabric host sets when the
 * bobber hooks something (sound/entity-state detection lives there). The state machine
 * itself — and therefore the timing and recast logic — is engine-side and unit-tested.
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
 * Row-based crop farm: walk forward and swing; when the host flags a row end ([atRowEndVar],
 * e.g. a wall/edge ahead), turn 180° and continue down the next row, alternating direction.
 *
 * The "am I at a row end?" detection is the host's job (raycast/collision → the variable);
 * the walk/turn/alternate logic here is engine-side and unit-tested.
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
