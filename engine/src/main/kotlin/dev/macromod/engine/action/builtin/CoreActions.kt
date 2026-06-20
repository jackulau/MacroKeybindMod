package dev.macromod.engine.action.builtin

import dev.macromod.engine.action.Args
import dev.macromod.engine.action.ExecutionContext
import dev.macromod.engine.action.ReturnValue
import dev.macromod.engine.action.ScriptAction
import dev.macromod.engine.text.convertAmpCodes
import dev.macromod.engine.value.Value

// --- output ---------------------------------------------------------------

object LogAction : ScriptAction("log") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue = ReturnValue.LogMsg(convertAmpCodes(ctx.expand(args[0])))
}

/** `echo(text)` — send text to the server as a chat packet (MKB ScriptActionEcho returns ReturnValueChat, perm group "chat"). */
object EchoAction : ScriptAction("echo") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue = ReturnValue.Chat(ctx.expand(args[0]))
}

/** Sends a line to the server (the explicit form of a bare chat line). */
object SendMessageAction : ScriptAction("sendmessage") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue = ReturnValue.Chat(ctx.expand(args[0]))
}

/** `lograw(json)` — emit a tellraw-style JSON line to local chat. */
object LogRawAction : ScriptAction("lograw") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.output.logRaw(ctx.expand(args[0]))
        return ReturnValue.Void
    }
}

/** `logto(target, text)` — emit text to a named target (file / textarea). */
object LogToAction : ScriptAction("logto") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val target = ctx.expand(args[0]).trim()
        val text = ctx.expand(args.getOrNull(1) ?: "")
        // MKB converts amp codes for non-file targets; a `.txt` target writes the raw line (ScriptActionLogTo).
        val out = if (target.endsWith(".txt", ignoreCase = true)) text else convertAmpCodes(text)
        ctx.output.logTo(target, out)
        return ReturnValue.Void
    }
}

/** `clearchat` — clear the local chat/log stream. */
object ClearChatAction : ScriptAction("clearchat") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.output.clearChat()
        return ReturnValue.Void
    }
}

/** `selectchannel(channel)` — select the inter-mod-comms channel for sends. */
object SelectChannelAction : ScriptAction("selectchannel") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.output.selectChannel(ctx.expand(args[0]).trim())
        return ReturnValue.Void
    }
}

// --- variables ------------------------------------------------------------

/** Strip a single layer of surrounding double-quotes (RHS of `:=`, etc.). */
private fun stripQuotes(s: String): String =
    if (s.length >= 2 && s.first() == '"' && s.last() == '"') s.substring(1, s.length - 1).replace("\\\"", "\"") else s

/** `:=` — store the RHS as a literal string (lazily `%var%`-expanded when later used). */
object SetAction : ScriptAction("set") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        // SET(<target>,[value]) — set to value, or TRUE when the value is omitted (documented contract).
        if (args.isEmpty()) return ReturnValue.Void
        val value = if (args.size >= 2) Value.Str(stripQuotes(args[1])) else Value.TRUE
        ctx.registry.setVariable(args[0].trim(), value)
        return ReturnValue.Void
    }
}

/** `=` — evaluate the RHS as an expression and store the typed result. */
object AssignAction : ScriptAction("assign") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        if (args.size >= 2) ctx.registry.setVariable(args[0].trim(), ctx.evaluate(args[1]))
        return ReturnValue.Void
    }
}

/** `inc([<#var>],[amount])` — the counter defaults to `#counter` when omitted (ScriptActionInc:19). */
object IncAction : ScriptAction("inc") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val name = args.getOrNull(0)?.trim()?.ifBlank { null } ?: "#counter"
        val by = if (args.size > 1) ctx.evaluate(args[1]).asInt() else 1
        ctx.registry.increment(name, by)
        return ReturnValue.Void
    }
}

/** `dec([<#var>],[amount])` — the counter defaults to `#counter` when omitted (mirrors [IncAction]). */
object DecAction : ScriptAction("dec") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val name = args.getOrNull(0)?.trim()?.ifBlank { null } ?: "#counter"
        val by = if (args.size > 1) ctx.evaluate(args[1]).asInt() else 1
        ctx.registry.increment(name, -by)
        return ReturnValue.Void
    }
}

object UnsetAction : ScriptAction("unset") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.registry.unsetVariable(args[0].trim())
        return ReturnValue.Void
    }
}

// --- expressions / values -------------------------------------------------

/** `iif(condition, ifTrue, ifFalse)` — inline conditional value (capturable). */
object IifAction : ScriptAction("iif") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val chosen = if (ctx.evaluate(args[0]).asBoolean()) ctx.expand(args[1]) else ctx.expand(args.getOrNull(2) ?: "")
        return ReturnValue.of(chosen)
    }
}

/** `calc(expr)` — evaluate and return the numeric/typed result (capturable). */
object CalcAction : ScriptAction("calc") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue = ReturnValue.of(ctx.evaluate(args[0]))
}

// --- string ops -----------------------------------------------------------

object LcaseAction : ScriptAction("lcase") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue = ReturnValue.of(ctx.expand(args[0]).lowercase())
}

object UcaseAction : ScriptAction("ucase") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue = ReturnValue.of(ctx.expand(args[0]).uppercase())
}

object LengthAction : ScriptAction("length") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue = ReturnValue.of(ctx.expand(args[0]).length)
}

/** `replace(text, find, with)`. */
object ReplaceAction : ScriptAction("replace") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue =
        ReturnValue.of(ctx.expand(args[0]).replace(ctx.expand(args[1]), ctx.expand(args.getOrNull(2) ?: "")))
}

/**
 * `indexof(text, needle)` → 0-based index of the substring (or -1), OR `indexof(array, value,
 * [casesensitive])` → 0-based index of the element when arg0 names a non-empty array. The array
 * form mirrors MKB `ScriptActionIndexOf` / `getArrayIndexOf` (default case-insensitive); the out-var
 * is the assignment LHS — `&i = indexof(...)`.
 */
object IndexOfAction : ScriptAction("indexof") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val array = ctx.registry.arrayValues(args[0].trim())
        if (array.isNotEmpty()) {
            val needle = ctx.expand(args[1])
            val caseSensitive = args.size > 2 && ctx.evaluate(args[2]).asBoolean()
            return ReturnValue.of(array.indexOfFirst { it.asString().equals(needle, ignoreCase = !caseSensitive) })
        }
        return ReturnValue.of(ctx.expand(args[0]).indexOf(ctx.expand(args[1])))
    }
}

// --- arrays ---------------------------------------------------------------

object PushAction : ScriptAction("push") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.registry.push(args[0].trim(), Value.Str(ctx.expand(args[1])))
        return ReturnValue.Void
    }
}

object PopAction : ScriptAction("pop") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue =
        ctx.registry.pop(args[0].trim())?.let { ReturnValue.of(it) } ?: ReturnValue.Void
}

object PutAction : ScriptAction("put") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.registry.put(args[0].trim(), Value.Str(ctx.expand(args[1])))
        return ReturnValue.Void
    }
}

object ArraySizeAction : ScriptAction("arraysize") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue =
        ReturnValue.of(ctx.registry.arrayValues(args[0].trim()).size)
}

/** Every built-in core action, for bulk registration. */
val CORE_ACTIONS: List<ScriptAction> = listOf(
    LogAction, EchoAction, SendMessageAction,
    LogRawAction, LogToAction, ClearChatAction, SelectChannelAction,
    SetAction, AssignAction, IncAction, DecAction, UnsetAction,
    IifAction, CalcAction,
    LcaseAction, UcaseAction, LengthAction, ReplaceAction, IndexOfAction,
    PushAction, PopAction, PutAction, ArraySizeAction,
)
