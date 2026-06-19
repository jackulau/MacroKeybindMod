package dev.macromod.engine.action.builtin

import dev.macromod.engine.action.Args
import dev.macromod.engine.action.ExecutionContext
import dev.macromod.engine.action.ReturnValue
import dev.macromod.engine.action.ScriptAction
import dev.macromod.engine.value.Value

/**
 * Task / config keywords. `store`/`storeover` have full engine semantics (list storage);
 * `isrunning`/`prompt` are best-effort engine forms; `exec`/`config`/`import`/`unimport` are
 * recognised here (so scripts using them compile and get visible feedback) but the full
 * task-scheduler + config-profile subsystem is deferred (a host concern, like the REPL).
 */

/** `store(name, value)` — append a value to the named list. */
object StoreAction : ScriptAction("store") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        if (args.size >= 2) ctx.registry.push(args[0].trim(), Value.Str(ctx.expand(args[1])))
        return ReturnValue.Void
    }
}

/** `storeover(name, value)` — replace the named list with a single value. */
object StoreOverAction : ScriptAction("storeover") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val name = args[0].trim()
        ctx.registry.clearArray(name)
        if (args.size >= 2) ctx.registry.push(name, Value.Str(ctx.expand(args[1])))
        return ReturnValue.Void
    }
}

/** `isrunning(macro)` — whether a macro is currently running. No async run-tracker yet → false. */
object IsRunningAction : ScriptAction("isrunning") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue = ReturnValue.of(false)
}

/** `prompt(&target, …)` — scripted prompt; with no runtime resolver the target is cleared. */
object PromptAction : ScriptAction("prompt") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        args.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { ctx.registry.setVariable(it.trim(), Value.Str("")) }
        return ReturnValue.Void
    }
}

/** A recognised keyword that reports its invocation (the full subsystem is host-side / deferred). */
private class LoggedAction(name: String) : ScriptAction(name) {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.output.log("[$name] ${(0 until args.size).joinToString(" ") { ctx.expand(args[it]) }}".trim())
        return ReturnValue.Void
    }
}

val EXEC_ACTION: ScriptAction = LoggedAction("exec")

/** `config(name)` — switch the active config profile (routed to the platform ConfigController). */
val CONFIG_ACTION: ScriptAction = object : ScriptAction("config") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        if (args.size >= 1) ctx.client.config.switchConfig(ctx.expand(args[0]).trim())
        return ReturnValue.Void
    }
}

/** `import(file)` — load a config/macro file (routed to the platform ConfigController). */
val IMPORT_ACTION: ScriptAction = object : ScriptAction("import") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        if (args.size >= 1) ctx.client.config.importConfig(ctx.expand(args[0]).trim())
        return ReturnValue.Void
    }
}

/** `unimport(file)` — unload a previously imported file (routed to the platform ConfigController). */
val UNIMPORT_ACTION: ScriptAction = object : ScriptAction("unimport") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        if (args.size >= 1) ctx.client.config.unimportConfig(ctx.expand(args[0]).trim())
        return ReturnValue.Void
    }
}

/** Task / config actions, for bulk registration. */
val TASK_CONFIG_ACTIONS: List<ScriptAction> = listOf(
    StoreAction, StoreOverAction, IsRunningAction, PromptAction,
    EXEC_ACTION, CONFIG_ACTION, IMPORT_ACTION, UNIMPORT_ACTION,
)
