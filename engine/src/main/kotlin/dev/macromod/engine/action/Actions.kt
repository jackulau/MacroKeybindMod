package dev.macromod.engine.action

import dev.macromod.engine.runtime.StackFrame
import dev.macromod.engine.value.Value
import dev.macromod.engine.variable.VariableRegistry

/** How an action participates in control flow. Most actions are [NORMAL]. */
enum class Operator {
    NORMAL,
    IF, ELSEIF, ELSE, ENDIF,
    LOOP_OPEN, LOOP_CLOSE,
    BREAK,
    UNSAFE_OPEN, UNSAFE_CLOSE,
}

/** Parsed, tokenised argument list for one action invocation. */
class Args(val raw: List<String>) {
    val size: Int get() = raw.size
    operator fun get(i: Int): String = raw.getOrElse(i) { "" }
    fun getOrNull(i: Int): String? = raw.getOrNull(i)
    fun isEmpty(): Boolean = raw.isEmpty()

    companion object {
        val EMPTY = Args(emptyList())
    }
}

/** Where an action's observable output goes. The Fabric host swaps in a real sink. */
interface OutputSink {
    fun chat(message: String)
    fun log(message: String)

    object NOOP : OutputSink {
        override fun chat(message: String) {}
        override fun log(message: String) {}
    }
}

/** Everything an action can see while it runs. Implemented by the runtime. */
interface ExecutionContext {
    val registry: VariableRegistry
    val output: OutputSink

    /** Runtime `%var%` expansion. [quoteStrings] wraps string values in quotes (for expression input). */
    fun expand(text: String, quoteStrings: Boolean = false): String

    /** Expand then evaluate an expression to a typed value. */
    fun evaluate(expression: String): Value

    fun resolve(variableName: String): Value?
}

/**
 * The result of running an action. [Void] cannot be captured into a variable; the
 * message-bearing kinds drive chat/log side effects; [Scalar]/[ArrayResult] are
 * capturable via an assignment's out-variable.
 */
sealed class ReturnValue {
    open val isVoid: Boolean get() = false
    open val localMessage: String? get() = null
    open val remoteMessage: String? get() = null
    open fun value(): Value = Value.ZERO

    object Void : ReturnValue() {
        override val isVoid: Boolean get() = true
    }

    class Scalar(private val v: Value) : ReturnValue() {
        override fun value(): Value = v
    }

    /** A line destined for the server (chat / command). */
    class Chat(message: String) : ReturnValue() {
        override val isVoid: Boolean get() = true
        override val remoteMessage: String? = message
    }

    /** A line shown locally (log overlay / client chat). */
    class LogMsg(message: String) : ReturnValue() {
        override val isVoid: Boolean get() = true
        override val localMessage: String? = message
    }

    class ArrayResult(val values: List<Value>, val append: Boolean = false) : ReturnValue()

    companion object {
        fun of(i: Int): ReturnValue = Scalar(Value.Num(i))
        fun of(s: String): ReturnValue = Scalar(Value.Str(s))
        fun of(b: Boolean): ReturnValue = Scalar(Value.Bool(b))
        fun of(v: Value): ReturnValue = Scalar(v)
    }
}

/**
 * A single scripting action (a DSL verb). Clean-room reimplementation of the
 * original's `IScriptAction` operator protocol: an action declares how it behaves
 * in control flow via [operator] and the hooks below; plain actions just override
 * [execute].
 */
abstract class ScriptAction(val name: String) {
    open val operator: Operator get() = Operator.NORMAL

    /** Normal action body. */
    open fun execute(ctx: ExecutionContext, args: Args): ReturnValue = ReturnValue.Void

    /** IF / ELSEIF condition truth. */
    open fun condition(ctx: ExecutionContext, args: Args): Boolean = true

    /** LOOP_OPEN pre-check: should the loop body run at least once? (`do` = true; `for`/`foreach` computed). */
    open fun enter(ctx: ExecutionContext, frame: StackFrame, args: Args): Boolean = true

    /** LOOP_CLOSE: continue looping? (`loop` = true forever; `while`/`until` evaluate; `next` delegates to opener). */
    open fun loopBack(ctx: ExecutionContext, frame: StackFrame, args: Args): Boolean = true

    /** for/foreach advancement, invoked by `next`. Returns true while more iterations remain. */
    open fun advanceLoop(ctx: ExecutionContext, frame: StackFrame): Boolean = false

    final override fun toString(): String = name
}

/** Placeholder for an unknown keyword — a no-op that keeps the program well-formed. */
class UnknownAction(name: String) : ScriptAction(name)

/** Keyword → action registry. First registration wins (so user/module actions can't clobber built-ins after the fact). */
class ActionRegistry {
    private val actions = HashMap<String, ScriptAction>()

    fun register(action: ScriptAction): Boolean {
        val key = action.name.lowercase()
        if (actions.containsKey(key)) return false
        actions[key] = action
        return true
    }

    fun registerAll(vararg actions: ScriptAction) = actions.forEach { register(it) }

    fun get(name: String): ScriptAction? = actions[name.lowercase()]

    fun has(name: String): Boolean = actions.containsKey(name.lowercase())

    fun names(): Set<String> = actions.keys.toSet()
}
