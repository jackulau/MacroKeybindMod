package dev.macromod.engine.action.builtin

import dev.macromod.engine.action.Args
import dev.macromod.engine.action.ExecutionContext
import dev.macromod.engine.action.Operator
import dev.macromod.engine.action.ReturnValue
import dev.macromod.engine.action.ScriptAction
import dev.macromod.engine.action.StopExecution
import dev.macromod.engine.text.stripFormattingCodes
import dev.macromod.engine.value.Value
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

// --- math -----------------------------------------------------------------

/**
 * `random([max[, min]])` → an INCLUSIVE random int in `[min, max]` (MKB `ScriptActionRandom`,
 * ACTIONS.md `RANDOM(<#target>,[max],[min])` "in [min,max]"). Bare `random()` → `[0, 100]`; one arg →
 * `[0, max]`; the target is captured via the out-variable (`#t = random(...)`). The bound is computed
 * in Long so a `max == Int.MAX_VALUE` upper bound never overflows `nextLong`.
 */
object RandomAction : ScriptAction("random") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        if (args.isEmpty()) return ReturnValue.of(Random.nextInt(0, 101)) // [0, 100]
        val max = ctx.evaluate(args[0]).asInt()
        val min = if (args.size >= 2) ctx.evaluate(args[1]).asInt() else 0
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        return ReturnValue.of(Random.nextLong(lo.toLong(), hi.toLong() + 1).toInt())
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

/** `sqrt(value)` → integer square root (the value model is integer-typed). Negatives → 0. */
object SqrtAction : ScriptAction("sqrt") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val n = ctx.evaluate(args[0]).asInt()
        return ReturnValue.of(if (n <= 0) 0 else sqrt(n.toDouble()).toInt())
    }
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

/**
 * `join(glue, &array[])` — concatenate array elements with `glue` between them.
 * MKB arg order is glue-FIRST (ScriptActionJoin:21-22; ACTIONS.md:69, DSL-REFERENCE.md:799).
 */
object JoinAction : ScriptAction("join") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val glue = ctx.expand(args[0])
        val array = args.getOrNull(1)?.trim() ?: ""
        return ReturnValue.of(ctx.registry.arrayValues(array).joinToString(glue) { it.asString() })
    }
}

// --- regex compile cache --------------------------------------------------
// Building a Regex runs Pattern.compile every call; the three regex actions below (regexreplace,
// match, ifmatches) each did so per-execute, recompiling up to 20x/s when they sit in an onTick
// macro or a loop body. Cache the compiled Regex by its post-expansion pattern (a constant pattern —
// the common case — then compiles once). Two maps keep the lookup key allocation-free and split the
// case flag. Single client thread, like Variable.parse's cache (variable/Variable.kt:49) and
// ScriptHost.programCache (ScriptHost.kt:60), so unsynchronized. Invalid patterns still throw on the
// miss-path compile, so each action's try/catch fallback stays intact.
private const val MAX_REGEX_CACHE = 128
private fun regexCacheMap() = object : LinkedHashMap<String, Regex>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, Regex>): Boolean = size > MAX_REGEX_CACHE
}
private val regexCache = regexCacheMap()
private val regexCacheIgnoreCase = regexCacheMap()

internal fun cachedRegex(pattern: String, ignoreCase: Boolean = false): Regex =
    if (ignoreCase) regexCacheIgnoreCase.getOrPut(pattern) { Regex(pattern, RegexOption.IGNORE_CASE) }
    else regexCache.getOrPut(pattern) { Regex(pattern) }

/** `regexreplace(text, pattern, replacement)` — invalid patterns leave the text unchanged. */
object RegexReplaceAction : ScriptAction("regexreplace") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val text = ctx.expand(args[0])
        return try {
            ReturnValue.of(text.replace(cachedRegex(ctx.expand(args[1])), ctx.expand(args.getOrNull(2) ?: "")))
        } catch (e: Exception) {
            ReturnValue.of(text)
        }
    }
}

/**
 * `match(text, pattern, [group], [default])` → a capture group of the first match (or the whole
 * match), else the default (or empty). Case-insensitive, matching the decompiled `ScriptActionMatch`
 * (`Pattern.compile(regex, 2)`): `[group]` picks the group (0 = whole match, 1+ = capture group,
 * coerced into range like MKB `Math.min/max`); `[default]` is returned when nothing matches. The
 * out-var is the assignment LHS — `&t = match(...)` — as with every capture-model action.
 */
object MatchAction : ScriptAction("match") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        return try {
            val m = cachedRegex(ctx.expand(args[1]), ignoreCase = true).find(ctx.expand(args[0]))
                ?: return ReturnValue.of(ctx.expand(args.getOrNull(3) ?: "")) // no match → default, else empty
            val groupArg = args.getOrNull(2)?.takeIf { it.isNotBlank() }
            val result = if (groupArg != null) {
                m.groupValues[ctx.evaluate(groupArg).asInt().coerceIn(0, m.groupValues.size - 1)]
            } else {
                if (m.groupValues.size > 1 && m.groupValues[1].isNotEmpty()) m.groupValues[1] else m.value
            }
            ReturnValue.of(result)
        } catch (e: Exception) {
            ReturnValue.of("")
        }
    }
}

/** `strip(text)` → remove Minecraft `§x` formatting/colour codes (capturable). */
object StripAction : ScriptAction("strip") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue =
        ReturnValue.of(stripFormattingCodes(ctx.expand(args[0])))
}

/** `encode(text)` → Base64 (UTF-8) encode (capturable). */
object EncodeAction : ScriptAction("encode") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue =
        ReturnValue.of(Base64.getEncoder().encodeToString(ctx.expand(args[0]).toByteArray(Charsets.UTF_8)))
}

/** `decode(text)` → Base64 decode; invalid input yields an empty string (capturable). */
object DecodeAction : ScriptAction("decode") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue = try {
        ReturnValue.of(String(Base64.getDecoder().decode(ctx.expand(args[0]).trim()), Charsets.UTF_8))
    } catch (e: IllegalArgumentException) {
        ReturnValue.of("")
    }
}

/**
 * `time([format])` → the current local date/time. `format` is a `SimpleDateFormat` pattern;
 * omitted (or invalid) falls back to `yyyy-MM-dd HH:mm:ss`. Captured via assignment
 * (`&now = time("HH:mm")`), matching the engine's out-variable convention.
 */
object TimeAction : ScriptAction("time") {
    private const val DEFAULT = "yyyy-MM-dd HH:mm:ss"
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val pattern = ctx.expand(args.getOrNull(0) ?: "").trim().ifEmpty { DEFAULT }
        val fmt = try { SimpleDateFormat(pattern) } catch (e: IllegalArgumentException) { SimpleDateFormat(DEFAULT) }
        return ReturnValue.of(fmt.format(Date()))
    }
}

// --- string conditionals (if-family operators) ----------------------------

// All four match case-INSENSITIVELY, mirroring the decompiled originals: ScriptActionIfContains
// lowercases both operands; IfBeginsWith/IfEndsWith additionally `.trim()` both; IfMatches compiles
// its regex with `Pattern.compile(pattern, 2)` (CASE_INSENSITIVE).

object IfContainsAction : ScriptAction("ifcontains") {
    override val operator get() = Operator.IF
    override fun condition(ctx: ExecutionContext, args: Args): Boolean =
        ctx.expand(args[0]).lowercase().contains(ctx.expand(args.getOrNull(1) ?: "").lowercase())
}

object IfBeginsWithAction : ScriptAction("ifbeginswith") {
    override val operator get() = Operator.IF
    override fun condition(ctx: ExecutionContext, args: Args): Boolean =
        ctx.expand(args[0]).lowercase().trim().startsWith(ctx.expand(args.getOrNull(1) ?: "").lowercase().trim())
}

object IfEndsWithAction : ScriptAction("ifendswith") {
    override val operator get() = Operator.IF
    override fun condition(ctx: ExecutionContext, args: Args): Boolean =
        ctx.expand(args[0]).lowercase().trim().endsWith(ctx.expand(args.getOrNull(1) ?: "").lowercase().trim())
}

object IfMatchesAction : ScriptAction("ifmatches") {
    override val operator get() = Operator.IF
    override fun condition(ctx: ExecutionContext, args: Args): Boolean = try {
        cachedRegex(ctx.expand(args.getOrNull(1) ?: ""), ignoreCase = true).containsMatchIn(ctx.expand(args[0]))
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

/**
 * `split(delimiter, source)` → an array of pieces (capture with `&out[] = split(...)`).
 * MKB arg order is delimiter-FIRST (ScriptActionSplit:23-24; ACTIONS.md:70, DSL-REFERENCE.md:798).
 * Kotlin `String.split(String)` is a LITERAL split, satisfying the original's `Pattern.quote(splitter)`;
 * an empty delimiter splits into characters.
 */
object SplitAction : ScriptAction("split") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val delimiter = ctx.expand(args[0])
        val source = ctx.expand(args.getOrNull(1) ?: "")
        val parts = if (delimiter.isEmpty()) source.map { it.toString() } else source.split(delimiter)
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

/**
 * `wait(time)` — suspend the script for a duration: suffix `ms` (milliseconds) or `t` (ticks),
 * otherwise seconds. Returns a [ReturnValue.Suspend]; the resumable interpreter pauses here and a
 * tick-paced host resumes it after the delay (20 ticks/second).
 */
object WaitAction : ScriptAction("wait") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val s = ctx.expand(args[0]).trim().lowercase()
        val ticks = when {
            s.endsWith("ms") -> (s.dropLast(2).trim().toDoubleOrNull() ?: 0.0) / 50.0
            s.endsWith("t") -> s.dropLast(1).trim().toDoubleOrNull() ?: 0.0
            else -> (s.toDoubleOrNull() ?: 0.0) * 20.0
        }
        return ReturnValue.Suspend(ticks.toInt().coerceAtLeast(0))
    }
}

/** Engine-agnostic string/math/flow actions, for bulk registration. */
val STRING_MATH_ACTIONS: List<ScriptAction> = listOf(
    RandomAction, AbsAction, MinAction, MaxAction, SqrtAction,
    SubstrAction, TrimAction, JoinAction, RegexReplaceAction, MatchAction,
    StripAction, EncodeAction, DecodeAction, TimeAction,
    IfContainsAction, IfBeginsWithAction, IfEndsWithAction, IfMatchesAction,
    ToggleAction, SplitAction, PassAction, StopAction, WaitAction,
)
