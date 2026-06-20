package dev.macromod.engine.action.builtin

import dev.macromod.engine.action.Args
import dev.macromod.engine.action.ExecutionContext
import dev.macromod.engine.action.ReturnValue
import dev.macromod.engine.action.ScriptAction

/**
 * The last MKB action keywords — chat-filter, auto-crafting, the custom-GUI builder, and the
 * REPL — so all 127 are recognised + routed. Each routes to a platform capability on
 * [dev.macromod.engine.action.ClientBridge]; engine-side they are thin, uniform routers tested
 * with recording fakes. The deep Fabric realizations (live GUI rendering, auto-craft execution,
 * REPL console) are layered in the host; here the keyword is recognised and reaches the platform.
 */

// --- chat-filter (ClientBridge.chatFilter) ---------------------------------

object ChatFilterAction : ScriptAction("chatfilter") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val raw = args.getOrNull(0)
        // MKB enables on the literal "on"/"1"/"true" (ScriptActionChatFilter); keep asBoolean as a
        // permissive superset (expressions, nonzero) and add the "on"/"off" keywords it would miss.
        val enabled = when {
            raw == null -> true
            else -> {
                val arg = ctx.expand(raw).trim()
                when {
                    arg.equals("on", ignoreCase = true) -> true
                    arg.equals("off", ignoreCase = true) -> false
                    else -> ctx.evaluate(arg).asBoolean()
                }
            }
        }
        ctx.client.chatFilter.setEnabled(enabled)
        return ReturnValue.Void
    }
}

object FilterAction : ScriptAction("filter") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue { ctx.client.chatFilter.filter(); return ReturnValue.Void }
}

object ModifyAction : ScriptAction("modify") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue { ctx.client.chatFilter.modify(ctx.expand(args[0])); return ReturnValue.Void }
}

// --- crafting / slots (ClientBridge.crafting) ------------------------------

object CraftAction : ScriptAction("craft") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.client.crafting.craft(ctx.expand(args[0]).trim(), if (args.size > 1) ctx.evaluate(args[1]).asInt() else 1, wait = false)
        return ReturnValue.Void
    }
}

object CraftAndWaitAction : ScriptAction("craftandwait") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.client.crafting.craft(ctx.expand(args[0]).trim(), if (args.size > 1) ctx.evaluate(args[1]).asInt() else 1, wait = true)
        return ReturnValue.Void
    }
}

object ClearCraftingAction : ScriptAction("clearcrafting") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue { ctx.client.crafting.clearCrafting(); return ReturnValue.Void }
}

object SetSlotItemAction : ScriptAction("setslotitem") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.client.crafting.setSlotItem(
            ctx.expand(args.getOrNull(0) ?: "").trim(),
            if (args.size > 1) ctx.evaluate(args[1]).asInt() else 0,
            if (args.size > 2) ctx.evaluate(args[2]).asInt() else 1,
        )
        return ReturnValue.Void
    }
}

object SlotClickAction : ScriptAction("slotclick") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.client.crafting.slotClick(
            ctx.evaluate(args[0]).asInt(),
            if (args.size > 1) ctx.evaluate(args[1]).asInt() else 0,
            if (args.size > 2) ctx.evaluate(args[2]).asBoolean() else false,
        )
        return ReturnValue.Void
    }
}

// --- custom-GUI builder (ClientBridge.guiBuilder) --------------------------

object ShowGuiAction : ScriptAction("showgui") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue { ctx.client.guiBuilder.showGui(ctx.expand(args[0]).trim()); return ReturnValue.Void }
}

object BindGuiAction : ScriptAction("bindgui") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.client.guiBuilder.bindGui(ctx.evaluate(args[0]).asInt(), ctx.expand(args.getOrNull(1) ?: "").trim())
        return ReturnValue.Void
    }
}

object SetLabelAction : ScriptAction("setlabel") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.client.guiBuilder.setLabel(ctx.expand(args[0]).trim(), ctx.expand(args.getOrNull(1) ?: ""))
        return ReturnValue.Void
    }
}

/** `getproperty(control, property)` -> the control's property value (capturable). */
object GetPropertyAction : ScriptAction("getproperty") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue =
        ReturnValue.of(ctx.client.guiBuilder.getProperty(ctx.expand(args[0]).trim(), ctx.expand(args.getOrNull(1) ?: "").trim()))
}

object SetPropertyAction : ScriptAction("setproperty") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.client.guiBuilder.setProperty(ctx.expand(args[0]).trim(), ctx.expand(args.getOrNull(1) ?: "").trim(), ctx.expand(args.getOrNull(2) ?: ""))
        return ReturnValue.Void
    }
}

// --- REPL ------------------------------------------------------------------

/** `repl` — open the REPL console (a host subsystem; recognised + reported here). */
object ReplAction : ScriptAction("repl") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue { ctx.output.log("[repl] open"); return ReturnValue.Void }
}

/** Chat-filter / crafting / GUI-builder / REPL actions, for bulk registration. */
val CHAT_CRAFT_GUI_ACTIONS: List<ScriptAction> = listOf(
    ChatFilterAction, FilterAction, ModifyAction,
    CraftAction, CraftAndWaitAction, ClearCraftingAction, SetSlotItemAction, SlotClickAction,
    ShowGuiAction, BindGuiAction, SetLabelAction, GetPropertyAction, SetPropertyAction,
    ReplAction,
)
