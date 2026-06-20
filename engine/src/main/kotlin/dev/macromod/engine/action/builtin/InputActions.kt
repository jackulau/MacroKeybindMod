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

/** `slot(n)` — select hotbar slot n (1-9). */
object SlotAction : ScriptAction("slot") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.input.slot(ctx.evaluate(args[0]).asInt())
        return ReturnValue.Void
    }
}

/** MKB clamps the scroll count: `count %= 9; if (count < 1) count = 1` (ScriptActionInventoryUp/Down). */
private fun scrollCount(ctx: ExecutionContext, args: Args): Int {
    if (args.isEmpty()) return 1
    val n = ctx.evaluate(args[0]).asInt() % 9
    return if (n < 1) 1 else n
}

/** `inventoryup([amount])` — scroll the hotbar towards slot 1 (default 1; count clamped %9, min 1). */
object InventoryUpAction : ScriptAction("inventoryup") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.input.scrollHotbar(-scrollCount(ctx, args))
        return ReturnValue.Void
    }
}

/** `inventorydown([amount])` — scroll the hotbar towards slot 9 (default 1; count clamped %9, min 1). */
object InventoryDownAction : ScriptAction("inventorydown") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.input.scrollHotbar(scrollCount(ctx, args))
        return ReturnValue.Void
    }
}

/** `type(text…)` — inject text; MKB joins ALL args with a space (ScriptActionType), so `type(a, b)` types "a b". */
object TypeAction : ScriptAction("type") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.input.type((0 until args.size).joinToString(" ") { ctx.expand(args[it]) })
        return ReturnValue.Void
    }
}

/** `togglekey(bind)` — flip a logical key's held state. */
object ToggleKeyAction : ScriptAction("togglekey") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.input.toggleKey(ctx.expand(args[0]).trim())
        return ReturnValue.Void
    }
}

/** `looks(yaw, [pitch], [time])` — turn to face yaw/pitch. v1 snaps (smooth-over-time is a follow-up). */
object LooksAction : ScriptAction("looks") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val yaw = ctx.expand(args[0]).trim().toFloatOrNull() ?: 0f
        val pitch = ctx.expand(args.getOrNull(1) ?: "").trim().toFloatOrNull() ?: 0f
        ctx.input.look(yaw, pitch)
        return ReturnValue.Void
    }
}

/** `sprint([0|off])` — start sprinting; a first arg of `0`/`off` stops it instead (ScriptActionSprint). */
object SprintAction : ScriptAction("sprint") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val arg0 = args.getOrNull(0)?.let { ctx.expand(it).trim() }
        if (arg0 == "0" || arg0.equals("off", ignoreCase = true)) ctx.input.release("sprint")
        else ctx.input.hold("sprint")
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
    SprintAction, UnsprintAction, LooksAction,
    SlotAction, InventoryUpAction, InventoryDownAction, TypeAction, ToggleKeyAction,
)
