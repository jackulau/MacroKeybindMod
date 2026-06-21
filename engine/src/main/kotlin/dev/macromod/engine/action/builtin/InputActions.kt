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

/**
 * MKB cardinal look keywords -> absolute MC yaw (ScriptActionLook: north=180/south=0/east=270/west=90,
 * the same convention calcyawto normalises to). `near` (snap to the nearest cardinal) needs live player
 * yaw, which the engine does not expose, so it falls through to the numeric path below.
 */
private val LOOK_CARDINALS = mapOf("north" to 180f, "south" to 0f, "east" to 270f, "west" to 90f)

/** Resolve look/looks args to an absolute (yaw, pitch): a cardinal keyword in arg0 (pitch 0), else raw float yaw/pitch. */
private fun lookTarget(ctx: ExecutionContext, args: Args): Pair<Float, Float> {
    val arg0 = ctx.expand(args[0]).trim()
    LOOK_CARDINALS[arg0.lowercase()]?.let { return it to 0f }
    val yaw = arg0.toFloatOrNull() ?: 0f
    val pitch = ctx.expand(args.getOrNull(1) ?: "").trim().toFloatOrNull() ?: 0f
    return yaw to pitch
}

/** `look(yaw, pitch)` — set absolute rotation in degrees; arg0 may be a cardinal (north/south/east/west). */
object LookAction : ScriptAction("look") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val (yaw, pitch) = lookTarget(ctx, args)
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

/**
 * `slot(n)` — select hotbar slot n (1-9). MKB's actionInventorySlot ignores an out-of-range
 * index (`if (slotId > 0 && slotId < 10)`, ScriptActionProvider.java:409): the current selection
 * is kept rather than clamped to an edge slot, so a `slot(%var%)` that computes outside 1..9 is a
 * no-op. Guard here (not in the host) so it stays headlessly testable.
 */
object SlotAction : ScriptAction("slot") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val n = ctx.evaluate(args[0]).asInt()
        if (n in 1..9) ctx.input.slot(n)
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

/** `looks(yaw, [pitch], [time])` — turn to face yaw/pitch (arg0 may be a cardinal). v1 snaps (smooth is a follow-up). */
object LooksAction : ScriptAction("looks") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val (yaw, pitch) = lookTarget(ctx, args)
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
