package dev.macromod.engine.action.builtin

import dev.macromod.engine.action.Args
import dev.macromod.engine.action.ExecutionContext
import dev.macromod.engine.action.ReturnValue
import dev.macromod.engine.action.ScriptAction

// --- world side-effects (route to ClientBridge.world) ----------------------

object RespawnAction : ScriptAction("respawn") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue { ctx.client.world.respawn(); return ReturnValue.Void }
}

object DisconnectAction : ScriptAction("disconnect") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue { ctx.client.world.disconnect(); return ReturnValue.Void }
}

/** `playsound(id)` — play a sound by registry id (e.g. "minecraft:entity.player.levelup"). */
object PlaySoundAction : ScriptAction("playsound") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue { ctx.client.world.playSound(ctx.expand(args[0]).trim()); return ReturnValue.Void }
}

/** `placesign(l1, l2, l3, l4)` — place/edit a sign with up to four lines. */
object PlaceSignAction : ScriptAction("placesign") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.client.world.placeSign((0 until args.size).map { ctx.expand(args[it]) })
        return ReturnValue.Void
    }
}

// --- HUD feedback (route to ClientBridge.hud) ------------------------------

/** `title(title, [subtitle])` — show a title / subtitle overlay. */
object TitleAction : ScriptAction("title") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.client.hud.title(ctx.expand(args[0]), ctx.expand(args.getOrNull(1) ?: ""))
        return ReturnValue.Void
    }
}

/** `toast(type, icon, text1, [text2], [ticks])` — a toast popup (text1/text2 are the lines). */
object ToastAction : ScriptAction("toast") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.client.hud.toast(ctx.expand(args.getOrNull(2) ?: ""), ctx.expand(args.getOrNull(3) ?: ""))
        return ReturnValue.Void
    }
}

/** `popupmessage(message, [animate])` — a message in the action-bar area. */
object PopupMessageAction : ScriptAction("popupmessage") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.client.hud.popup(ctx.expand(args[0]))
        return ReturnValue.Void
    }
}

/** `gui([name])` — open a vanilla GUI screen (e.g. "inventory"), or close the current one. */
object GuiAction : ScriptAction("gui") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.client.hud.openGui(ctx.expand(args.getOrNull(0) ?: "").trim())
        return ReturnValue.Void
    }
}

/** World + HUD actions, for bulk registration. */
val WORLD_HUD_ACTIONS: List<ScriptAction> = listOf(
    RespawnAction, DisconnectAction, PlaySoundAction, PlaceSignAction,
    TitleAction, ToastAction, PopupMessageAction, GuiAction,
)
