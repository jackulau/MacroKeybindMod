package dev.macromod.engine.action.builtin

import dev.macromod.engine.action.Args
import dev.macromod.engine.action.ExecutionContext
import dev.macromod.engine.action.ReturnValue
import dev.macromod.engine.action.ScriptAction

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

/** Navigation actions. Their bodies call the platform [dev.macromod.engine.action.Navigator]. */
val NAV_ACTIONS: List<ScriptAction> = listOf(GotoAction, StopNavAction)
