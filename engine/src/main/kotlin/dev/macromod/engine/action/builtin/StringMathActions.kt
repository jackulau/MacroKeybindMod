package dev.macromod.engine.action.builtin

import dev.macromod.engine.action.Args
import dev.macromod.engine.action.ExecutionContext
import dev.macromod.engine.action.Operator
import dev.macromod.engine.action.ReturnValue
import dev.macromod.engine.action.ScriptAction
import dev.macromod.engine.action.StopExecution
import dev.macromod.engine.value.Value
import kotlin.math.abs
import kotlin.random.Random

// --- math -----------------------------------------------------------------

/** `random(max)` → 0..max-1; `random(min, max)` → min..max inclusive. */
object RandomAction : ScriptAction("random") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue = when {
        args.size >= 2 -> {
            val lo = ctx.evaluate(args[0]).asInt()
            val hi = ctx.evaluate(args[1]).asInt()
            val a = minOf(lo, hi)
            val b = maxOf(lo, hi)
            ReturnValue.of(Random.nextInt(a, b + 1))
        }
        args.size == 1 -> {
            val n = ctx.evaluate(args[0]).asInt()
            ReturnValue.of(if (n <= 0) 0 else Random.nextInt(n))
        }
        else -> ReturnValue.of(Random.nextInt())
    }
}

object AbsAction : ScriptAction("abs") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue = ReturnValue.of(abs(ctx.evaluate(args[0]).asInt()))
}

object MinAction : ScriptAction("min") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue =
        ReturnValue.of(minOf(ctx.evaluate(args[0]).asInt(), ctx.evaluate(args[1]).asInt()))
}

object MaxAction : ScriptAction("max") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue =
        ReturnValue.of(maxOf(ctx.evaluate(args[0]).asInt(), ctx.evaluate(args[1]).asInt()))
}

// --- string ---------------------------------------------------------------

/** `substr(text, start [, length])` — length omitted = to end; bounds are clamped. */
object SubstrAction : ScriptAction("substr") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val s = ctx.expand(args[0])
        val start = ctx.evaluate(args[1]).asInt().coerceIn(0, s.length)
        val end = if (args.size > 2) (start + ctx.evaluate(args[2]).asInt()).coerceIn(start, s.length) else s.length
        return ReturnValue.of(s.substring(start, end))
    }
}

object TrimAction : ScriptAction("trim") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue = ReturnValue.of(ctx.expand(args[0]).trim())
}

/** `join(&array[], separator)` — concatenate array elements (separator optional). */
object JoinAction : ScriptAction("join") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val sep = if (args.size > 1) ctx.expand(args[1]) else ""
        return ReturnValue.of(ctx.registry.arrayValues(args[0].trim()).joinToString(sep) { it.asString() })
    }
}

/** `regexreplace(text, pattern, replacement)` — invalid patterns leave the text unchanged. */
object RegexReplaceAction : ScriptAction("regexreplace") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val text = ctx.expand(args[0])
        return try {
            ReturnValue.of(text.replace(Regex(ctx.expand(args[1])), ctx.expand(args.getOrNull(2) ?: "")))
        } catch (e: Exception) {
            ReturnValue.of(text)
        }
    }
}

/** `match(text, pattern)` → the first capture group (or whole match), else empty. */
object MatchAction : ScriptAction("match") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        return try {
            val m = Regex(ctx.expand(args[1])).find(ctx.expand(args[0]))
            ReturnValue.of(m?.let { if (it.groupValues.size > 1 && it.groupValues[1].isNotEmpty()) it.groupValues[1] else it.value } ?: "")
        } catch (e: Exception) {
            ReturnValue.of("")
        }
    }
}

// --- string conditionals (if-family operators) ----------------------------

object IfContainsAction : ScriptAction("ifcontains") {
    override val operator get() = Operator.IF
    override fun condition(ctx: ExecutionContext, args: Args): Boolean =
        ctx.expand(args[0]).contains(ctx.expand(args.getOrNull(1) ?: ""))
}

object IfBeginsWithAction : ScriptAction("ifbeginswith") {
    override val operator get() = Operator.IF
    override fun condition(ctx: ExecutionContext, args: Args): Boolean =
        ctx.expand(args[0]).startsWith(ctx.expand(args.getOrNull(1) ?: ""))
}

object IfEndsWithAction : ScriptAction("ifendswith") {
    override val operator get() = Operator.IF
    override fun condition(ctx: ExecutionContext, args: Args): Boolean =
        ctx.expand(args[0]).endsWith(ctx.expand(args.getOrNull(1) ?: ""))
}

object IfMatchesAction : ScriptAction("ifmatches") {
    override val operator get() = Operator.IF
    override fun condition(ctx: ExecutionContext, args: Args): Boolean = try {
        Regex(ctx.expand(args.getOrNull(1) ?: "")).containsMatchIn(ctx.expand(args[0]))
    } catch (e: Exception) {
        false
    }
}

// --- flow / misc ----------------------------------------------------------

/** `toggle(flag)` — flip a boolean variable. */
object ToggleAction : ScriptAction("toggle") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val name = args[0].trim()
        val current = ctx.registry.getVariable(name)?.asBoolean() ?: false
        ctx.registry.setVariable(name, Value.Bool(!current))
        return ReturnValue.Void
    }
}

/** `split(text, separator)` → an array of pieces (capture with `&out[] = split(...)`). */
object SplitAction : ScriptAction("split") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val text = ctx.expand(args[0])
        val sep = ctx.expand(args.getOrNull(1) ?: ",")
        val parts = if (sep.isEmpty()) text.map { it.toString() } else text.split(sep)
        return ReturnValue.ArrayResult(parts.map { Value.Str(it) })
    }
}

/** `pass` — explicit no-op. */
object PassAction : ScriptAction("pass") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue = ReturnValue.Void
}

/** `stop` — end the macro immediately. */
object StopAction : ScriptAction("stop") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue = throw StopExecution()
}

/** Engine-agnostic string/math/flow actions, for bulk registration. */
val STRING_MATH_ACTIONS: List<ScriptAction> = listOf(
    RandomAction, AbsAction, MinAction, MaxAction,
    SubstrAction, TrimAction, JoinAction, RegexReplaceAction, MatchAction,
    IfContainsAction, IfBeginsWithAction, IfEndsWithAction, IfMatchesAction,
    ToggleAction, SplitAction, PassAction, StopAction,
)
