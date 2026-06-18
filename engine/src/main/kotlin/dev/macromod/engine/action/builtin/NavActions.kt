package dev.macromod.engine.action.builtin

import dev.macromod.engine.action.Args
import dev.macromod.engine.action.ExecutionContext
import dev.macromod.engine.action.ReturnValue
import dev.macromod.engine.action.ScriptAction
import dev.macromod.engine.value.Value
import kotlin.math.atan2
import kotlin.math.hypot

/** `goto(x, y, z)` — navigate to a block position; returns whether a path was found (capturable). */
object GotoAction : ScriptAction("goto") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val x = ctx.evaluate(args[0]).asInt()
        val y = ctx.evaluate(args[1]).asInt()
        val z = ctx.evaluate(args[2]).asInt()
        return ReturnValue.of(ctx.navigator.pathTo(x, y, z))
    }
}

/** `stopnav` — cancel any active navigation. */
object StopNavAction : ScriptAction("stopnav") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.navigator.stop()
        return ReturnValue.Void
    }
}

/**
 * `calcyawto(x, z [, #yawOut] [, #distOut])` — the absolute Minecraft yaw (and horizontal
 * distance) from the player to the target column. Reads the player's position from the
 * environment (`%XPOS%`/`%ZPOS%`, supplied by the Fabric host), so it is pure logic and
 * unit-testable with a fake env. Yaw uses MC's convention (0 = +Z south), `atan2(-dx, dz)`.
 * Writes the optional out-vars and also returns the yaw (capturable).
 */
object CalcYawToAction : ScriptAction("calcyawto") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val dx = (ctx.evaluate(args[0]).asInt() - (ctx.resolve("XPOS")?.asInt() ?: 0)).toDouble()
        val dz = (ctx.evaluate(args[1]).asInt() - (ctx.resolve("ZPOS")?.asInt() ?: 0)).toDouble()
        val yaw = Math.toDegrees(atan2(-dx, dz)).toInt()
        val dist = hypot(dx, dz).toInt()
        args.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { ctx.registry.setVariable(it.trim(), Value.Num(yaw)) }
        args.getOrNull(3)?.takeIf { it.isNotBlank() }?.let { ctx.registry.setVariable(it.trim(), Value.Num(dist)) }
        return ReturnValue.of(yaw)
    }
}

/** Navigation actions. Their bodies call the platform [dev.macromod.engine.action.Navigator]. */
val NAV_ACTIONS: List<ScriptAction> = listOf(GotoAction, StopNavAction, CalcYawToAction)
