package dev.macromod.engine.action.builtin

import dev.macromod.engine.action.Args
import dev.macromod.engine.action.ExecutionContext
import dev.macromod.engine.action.ReturnValue
import dev.macromod.engine.action.ScriptAction
import dev.macromod.engine.text.convertAmpCodes

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
        // MKB ScriptActionChatFilter:23-24 — NO arg TOGGLES (`!isEnabled()`); an arg enables on the
        // literal "1"/"on"/"true". We keep asBoolean as a permissive superset (expressions, nonzero)
        // and add the "on"/"off" keywords the literal match would miss.
        val enabled = when {
            raw == null -> !ctx.client.chatFilter.isEnabled()
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
        // MKB returns the new state (ScriptActionChatFilter:32 `new ReturnValue(isEnabled())`);
        // ReturnValue.setBool stringifies "True"/"False" == our Value.Bool, so this is capturable + faithful.
        return ReturnValue.of(enabled)
    }
}

object FilterAction : ScriptAction("filter") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue { ctx.client.chatFilter.filter(); return ReturnValue.Void }
}

object ModifyAction : ScriptAction("modify") {
    // MKB rewrites the filtered chat line with &->§ colour-code conversion (ScriptActionModify.java:21,
    // Util.convertAmpCodes) — the same treatment title/toast/popup got in goal-040.
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue { ctx.client.chatFilter.modify(convertAmpCodes(ctx.expand(args[0]))); return ReturnValue.Void }
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
        // MKB parses the mouse-button as a string: a token starting with `r` => right (button 1), else 0
        // (ScriptActionSlotClick.java:42-43). Superset: a bare numeric button still passes through, so both
        // `slotclick(0, right)` and the legacy `slotclick(0, 1)` right-click.
        val btn = ctx.expand(args.getOrNull(1) ?: "").trim()
        val button = if (btn.startsWith("r", ignoreCase = true)) 1 else btn.toIntOrNull() ?: 0
        ctx.client.crafting.slotClick(
            ctx.evaluate(args[0]).asInt(),
            button,
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
        // MKB stores labels in its `&`-code form: the text is normalised §->& (ScriptActionSetLabel.java:19).
        // The label NAME is not converted (MKB only converts the text and the bridge-gated binding).
        ctx.client.guiBuilder.setLabel(ctx.expand(args[0]).trim(), ctx.expand(args.getOrNull(1) ?: "").replace('§', '&'))
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
        // MKB stores the property VALUE in its &-code form: §->& normalised (ScriptActionSetProperty.java:32),
        // matching the setlabel text treatment (goal-043). The control + property names are left untouched.
        ctx.client.guiBuilder.setProperty(
            ctx.expand(args[0]).trim(),
            ctx.expand(args.getOrNull(1) ?: "").trim(),
            ctx.expand(args.getOrNull(2) ?: "").replace('§', '&'),
        )
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
