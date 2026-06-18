package dev.macromod.engine.action.builtin

import dev.macromod.engine.action.Args
import dev.macromod.engine.action.ExecutionContext
import dev.macromod.engine.action.ReturnValue
import dev.macromod.engine.action.ScriptAction

/** `key(name)` — tap a logical key for one tick (attack/use/jump/forward/…). */
object KeyAction : ScriptAction("key") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.input.tap(ctx.expand(args[0]).trim())
        return ReturnValue.Void
    }
}

/** `keydown(name)` — press and hold a key. */
object KeyDownAction : ScriptAction("keydown") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.input.hold(ctx.expand(args[0]).trim())
        return ReturnValue.Void
    }
}

/** `keyup(name)` — release a held key. */
object KeyUpAction : ScriptAction("keyup") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.input.release(ctx.expand(args[0]).trim())
        return ReturnValue.Void
    }
}

/** `press(name)` — alias for [KeyAction]. */
object PressAction : ScriptAction("press") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.input.tap(ctx.expand(args[0]).trim())
        return ReturnValue.Void
    }
}

/** `look(yaw, pitch)` — set absolute rotation in degrees. */
object LookAction : ScriptAction("look") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val yaw = ctx.expand(args[0]).trim().toFloatOrNull() ?: 0f
        val pitch = ctx.expand(args.getOrNull(1) ?: "").trim().toFloatOrNull() ?: 0f
        ctx.input.look(yaw, pitch)
        return ReturnValue.Void
    }
}

/** `turn(deltaYaw, deltaPitch)` — rotate by a delta in degrees. */
object TurnAction : ScriptAction("turn") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val dYaw = ctx.expand(args[0]).trim().toFloatOrNull() ?: 0f
        val dPitch = ctx.expand(args.getOrNull(1) ?: "").trim().toFloatOrNull() ?: 0f
        ctx.input.turn(dYaw, dPitch)
        return ReturnValue.Void
    }
}

/** `sprint()` — start sprinting (hold the sprint key). */
object SprintAction : ScriptAction("sprint") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.input.hold("sprint")
        return ReturnValue.Void
    }
}

/** `unsprint()` — stop sprinting (release the sprint key). */
object UnsprintAction : ScriptAction("unsprint") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.input.release("sprint")
        return ReturnValue.Void
    }
}

/** Input actions (player control). Their bodies call the platform [dev.macromod.engine.action.InputController]. */
val INPUT_ACTIONS: List<ScriptAction> = listOf(
    KeyAction, KeyDownAction, KeyUpAction, PressAction, LookAction, TurnAction,
    SprintAction, UnsprintAction,
)
