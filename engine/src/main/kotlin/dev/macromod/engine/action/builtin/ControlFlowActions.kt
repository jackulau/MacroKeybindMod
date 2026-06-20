package dev.macromod.engine.action.builtin

import dev.macromod.engine.action.Args
import dev.macromod.engine.action.ExecutionContext
import dev.macromod.engine.action.Operator
import dev.macromod.engine.action.ScriptAction
import dev.macromod.engine.runtime.StackFrame
import dev.macromod.engine.value.Value
import dev.macromod.engine.variable.IteratorBundle

// --- conditionals ---------------------------------------------------------

object IfAction : ScriptAction("if") {
    override val operator get() = Operator.IF
    override fun condition(ctx: ExecutionContext, args: Args): Boolean = ctx.evaluate(args[0]).asBoolean()
}

object ElseIfAction : ScriptAction("elseif") {
    override val operator get() = Operator.ELSEIF
    override fun condition(ctx: ExecutionContext, args: Args): Boolean =
        if (args.isEmpty()) true else ctx.evaluate(args[0]).asBoolean()
}

object ElseAction : ScriptAction("else") {
    override val operator get() = Operator.ELSE
}

object EndIfAction : ScriptAction("endif") {
    override val operator get() = Operator.ENDIF
}

// --- loops ----------------------------------------------------------------

/** Per-frame state for a counted `do(N)`. `unlimited` = the no-arg `do` (loop until `break`/condition). */
private class DoState(val count: Int, var current: Int, val unlimited: Boolean)

/**
 * `do` loops until `break` (or a `while`/`until` closer); `do(N)` runs the body exactly N times
 * (MKB `ScriptActionDo.State`, ACTIONS.md `DO([count])` "optional max iteration count"). The count is
 * enforced through [advanceLoop], which every closer delegates to, so `do(N)...while(cond)` caps at N
 * AND honours the condition, while the no-arg `do` stays byte-identical (advanceLoop returns true).
 */
object DoAction : ScriptAction("do") {
    override val operator get() = Operator.LOOP_OPEN
    override fun enter(ctx: ExecutionContext, frame: StackFrame, args: Args): Boolean {
        val raw = args.getOrNull(0)?.trim()
        if (raw.isNullOrEmpty()) { frame.loopState = DoState(0, 0, unlimited = true); return true }
        val count = ctx.evaluate(raw).asInt()
        frame.loopState = DoState(count, current = 1, unlimited = false)
        return count >= 1 // do(0) runs zero times; do(N>=1) runs the first iteration
    }

    override fun advanceLoop(ctx: ExecutionContext, frame: StackFrame): Boolean {
        val st = frame.loopState as? DoState ?: return true
        if (st.unlimited) return true
        st.current++
        return st.current <= st.count
    }
}

object LoopAction : ScriptAction("loop") {
    override val operator get() = Operator.LOOP_CLOSE
    // Delegate to the `do` opener: unlimited -> loop until `break`; counted -> stop after N iterations.
    override fun loopBack(ctx: ExecutionContext, frame: StackFrame, args: Args): Boolean = frame.opener.advanceLoop(ctx, frame)
}

object WhileAction : ScriptAction("while") {
    override val operator get() = Operator.LOOP_CLOSE
    override fun loopBack(ctx: ExecutionContext, frame: StackFrame, args: Args): Boolean =
        ctx.evaluate(args[0]).asBoolean() && frame.opener.advanceLoop(ctx, frame)
}

object UntilAction : ScriptAction("until") {
    override val operator get() = Operator.LOOP_CLOSE
    override fun loopBack(ctx: ExecutionContext, frame: StackFrame, args: Args): Boolean =
        !ctx.evaluate(args[0]).asBoolean() && frame.opener.advanceLoop(ctx, frame)
}

private class ForState(val varName: String, var current: Int, val end: Int, val step: Int)

/** `for(#i, start, end [, step])` — numeric counted loop (pre-check: may run zero times). */
object ForAction : ScriptAction("for") {
    override val operator get() = Operator.LOOP_OPEN
    override fun enter(ctx: ExecutionContext, frame: StackFrame, args: Args): Boolean {
        val varName = args[0].trim()
        val start = ctx.evaluate(args[1]).asInt()
        val end = ctx.evaluate(args[2]).asInt()
        val step = if (args.size > 3) ctx.evaluate(args[3]).asInt().let { if (it == 0) 1 else it } else 1
        ctx.registry.setVariable(varName, Value.Num(start))
        frame.loopState = ForState(varName, start, end, step)
        return if (step > 0) start <= end else start >= end
    }

    override fun advanceLoop(ctx: ExecutionContext, frame: StackFrame): Boolean {
        val st = frame.loopState as? ForState ?: return false
        // Advance in Long so the counter can't wrap past Int.MAX/MIN and keep the loop condition
        // true forever (a runaway that previously ran ~1M times before hitting the step cap and
        // throwing). If the next value leaves Int range, the loop is finished — terminate.
        val next = st.current.toLong() + st.step
        if (next < Int.MIN_VALUE.toLong() || next > Int.MAX_VALUE.toLong()) return false
        st.current = next.toInt()
        ctx.registry.setVariable(st.varName, Value.Num(st.current))
        return if (st.step > 0) st.current <= st.end else st.current >= st.end
    }
}

private class ForeachState(
    val varName: String,
    val values: List<Value>,
    val bundles: List<IteratorBundle>?,
    var nextIndex: Int,
    val posVarName: String?,
)

/**
 * `foreach(&item, &array[], [#pos])` — iterate the elements of an array (pre-check: zero if empty).
 * The optional 3rd arg is a 0-based position variable, set to the index of the element bound on
 * each iteration (matches the decompiled MKB `ScriptActionForEach` optional params[2] +
 * `ScriptedIteratorArray`, which binds `offsetVar -> offset` over `0 until size`).
 */
object ForEachAction : ScriptAction("foreach") {
    override val operator get() = Operator.LOOP_OPEN
    override fun enter(ctx: ExecutionContext, frame: StackFrame, args: Args): Boolean {
        val varName = args[0].trim()
        val target = args[1].trim()
        val posVarName = args.getOrNull(2)?.trim()?.ifBlank { null }
        // A multi-var (bundle) iterator (e.g. effects) takes precedence: it binds the loop var to a
        // primary AND exposes fixed-name vars (%EFFECTNAME% …) per element. Otherwise the single-var
        // path: a named iterator (env / running / players / …), then array fallback.
        val bundles = ctx.registry.iteratorBundles(target)
        if (bundles != null) {
            frame.loopState = ForeachState(varName, emptyList(), bundles, 1, posVarName)
            if (bundles.isEmpty()) return false
            bindBundle(ctx, varName, bundles[0])
            posVarName?.let { ctx.registry.setVariable(it, Value.Num(0)) }
            return true
        }
        val values = ctx.registry.iteratorValues(target) ?: ctx.registry.arrayValues(target)
        frame.loopState = ForeachState(varName, values, null, 1, posVarName)
        if (values.isEmpty()) return false
        ctx.registry.setVariable(varName, values[0])
        posVarName?.let { ctx.registry.setVariable(it, Value.Num(0)) }
        return true
    }

    override fun advanceLoop(ctx: ExecutionContext, frame: StackFrame): Boolean {
        val st = frame.loopState as? ForeachState ?: return false
        if (st.bundles != null) {
            if (st.nextIndex >= st.bundles.size) return false
            bindBundle(ctx, st.varName, st.bundles[st.nextIndex])
            st.posVarName?.let { ctx.registry.setVariable(it, Value.Num(st.nextIndex)) }
            st.nextIndex++
            return true
        }
        if (st.nextIndex >= st.values.size) return false
        ctx.registry.setVariable(st.varName, st.values[st.nextIndex])
        st.posVarName?.let { ctx.registry.setVariable(it, Value.Num(st.nextIndex)) }
        st.nextIndex++
        return true
    }

    /** Bind the loop var to the bundle's primary value and expose its fixed-name vars to the body. */
    private fun bindBundle(ctx: ExecutionContext, varName: String, bundle: IteratorBundle) {
        ctx.registry.setVariable(varName, bundle.loopValue)
        for ((k, v) in bundle.vars) ctx.registry.setTransient(k, v)
    }
}

object NextAction : ScriptAction("next") {
    override val operator get() = Operator.LOOP_CLOSE
    override fun loopBack(ctx: ExecutionContext, frame: StackFrame, args: Args): Boolean =
        frame.opener.advanceLoop(ctx, frame)
}

object BreakAction : ScriptAction("break") {
    override val operator get() = Operator.BREAK
}

object UnsafeAction : ScriptAction("unsafe") {
    override val operator get() = Operator.UNSAFE_OPEN
}

object EndUnsafeAction : ScriptAction("endunsafe") {
    override val operator get() = Operator.UNSAFE_CLOSE
}

/** Every built-in control-flow action, for bulk registration. */
val CONTROL_FLOW_ACTIONS: List<ScriptAction> = listOf(
    IfAction, ElseIfAction, ElseAction, EndIfAction,
    DoAction, LoopAction, WhileAction, UntilAction,
    ForAction, ForEachAction, NextAction, BreakAction,
    UnsafeAction, EndUnsafeAction,
)
