package dev.macromod.engine.action.builtin

import dev.macromod.engine.action.Args
import dev.macromod.engine.action.ExecutionContext
import dev.macromod.engine.action.Operator
import dev.macromod.engine.action.ScriptAction
import dev.macromod.engine.runtime.StackFrame
import dev.macromod.engine.value.Value

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

object DoAction : ScriptAction("do") {
    override val operator get() = Operator.LOOP_OPEN
    override fun enter(ctx: ExecutionContext, frame: StackFrame, args: Args): Boolean = true // do-while: body runs once
}

object LoopAction : ScriptAction("loop") {
    override val operator get() = Operator.LOOP_CLOSE
    override fun loopBack(ctx: ExecutionContext, frame: StackFrame, args: Args): Boolean = true // until `break`
}

object WhileAction : ScriptAction("while") {
    override val operator get() = Operator.LOOP_CLOSE
    override fun loopBack(ctx: ExecutionContext, frame: StackFrame, args: Args): Boolean = ctx.evaluate(args[0]).asBoolean()
}

object UntilAction : ScriptAction("until") {
    override val operator get() = Operator.LOOP_CLOSE
    override fun loopBack(ctx: ExecutionContext, frame: StackFrame, args: Args): Boolean = !ctx.evaluate(args[0]).asBoolean()
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
        st.current += st.step
        ctx.registry.setVariable(st.varName, Value.Num(st.current))
        return if (st.step > 0) st.current <= st.end else st.current >= st.end
    }
}

private class ForeachState(val varName: String, val values: List<Value>, var nextIndex: Int)

/** `foreach(&item, &array[])` — iterate the elements of an array (pre-check: zero if empty). */
object ForEachAction : ScriptAction("foreach") {
    override val operator get() = Operator.LOOP_OPEN
    override fun enter(ctx: ExecutionContext, frame: StackFrame, args: Args): Boolean {
        val varName = args[0].trim()
        val values = ctx.registry.arrayValues(args[1].trim())
        frame.loopState = ForeachState(varName, values, 1)
        if (values.isEmpty()) return false
        ctx.registry.setVariable(varName, values[0])
        return true
    }

    override fun advanceLoop(ctx: ExecutionContext, frame: StackFrame): Boolean {
        val st = frame.loopState as? ForeachState ?: return false
        if (st.nextIndex >= st.values.size) return false
        ctx.registry.setVariable(st.varName, st.values[st.nextIndex])
        st.nextIndex++
        return true
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
