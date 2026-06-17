package dev.macromod.engine.runtime

import dev.macromod.engine.action.Args
import dev.macromod.engine.action.ExecutionContext
import dev.macromod.engine.action.Operator
import dev.macromod.engine.action.OutputSink
import dev.macromod.engine.action.ReturnValue
import dev.macromod.engine.action.ScriptAction
import dev.macromod.engine.action.StopExecution
import dev.macromod.engine.ast.Instruction
import dev.macromod.engine.expr.ExpressionEvaluator
import dev.macromod.engine.param.VariableExpander
import dev.macromod.engine.value.Value
import dev.macromod.engine.variable.VariableRegistry

class ScriptException(message: String) : RuntimeException(message)

/**
 * One control-flow frame on the interpreter's operator stack.
 *
 *  - [bodyStart] is the index of the first body instruction (loop rewind target).
 *  - [conditionalFlag] gates whether this frame's body is live; the interpreter's
 *    "execution state" is the AND of every frame's flag.
 *  - [ifFlag] (sticky) records whether any branch of an if/elseif chain matched.
 *  - [loopState] holds per-loop iteration state (for/foreach).
 */
class StackFrame(
    val opener: ScriptAction,
    val openerArgs: Args,
    var bodyStart: Int,
    var conditionalFlag: Boolean,
    var ifFlag: Boolean = conditionalFlag,
    var elseFlag: Boolean = false,
    var loopState: Any? = null,
)

/**
 * Executes a compiled program (flat `List<Instruction>`) using a single instruction
 * pointer + an operator stack. Faithful in behaviour to the original engine; cleaner
 * in mechanism (loops keep their frame and rewind to [StackFrame.bodyStart] rather
 * than the original's pop-and-republish dance).
 */
class Interpreter(
    private val program: List<Instruction>,
    private val ctx: ExecutionContext,
    private val maxSteps: Int = 1_000_000,
    private val maxStackDepth: Int = 32,
) {
    private val stack = ArrayDeque<StackFrame>()
    private var pointer = 0
    var steps = 0
        private set

    /** AND of every frame's conditional flag (empty stack → live). */
    private fun live(): Boolean = stack.all { it.conditionalFlag }

    /** Live state excluding the top frame (used when (re)evaluating elseif/else against the parent). */
    private fun parentLive(): Boolean = stack.drop(1).all { it.conditionalFlag }

    fun run() {
        try {
            while (pointer < program.size) {
                if (++steps > maxSteps) throw ScriptException("max steps ($maxSteps) exceeded — possible infinite loop")
                when (val ins = program[pointer]) {
                    is Instruction.ChatLine -> { if (live()) emitChat(ins.text); pointer++ }
                    is Instruction.Invoke -> dispatch(ins)
                }
            }
        } catch (e: StopExecution) {
            return // `stop` ends the macro immediately; open blocks are fine
        }
        if (stack.isNotEmpty()) {
            throw ScriptException("unterminated block: missing closer for '${stack.first().opener.name}'")
        }
    }

    private fun dispatch(ins: Instruction.Invoke) {
        when (ins.action.operator) {
            Operator.IF -> { handleIf(ins); pointer++ }
            Operator.ELSEIF -> { handleElseIf(ins); pointer++ }
            Operator.ELSE -> { handleElse(); pointer++ }
            Operator.ENDIF -> { popFrame(); pointer++ }
            Operator.LOOP_OPEN -> { handleLoopOpen(ins); pointer++ }
            Operator.LOOP_CLOSE -> handleLoopClose(ins) // manages the pointer itself
            Operator.BREAK -> { if (live()) handleBreak(); pointer++ }
            Operator.UNSAFE_OPEN -> { push(StackFrame(ins.action, ins.args, pointer + 1, live())); pointer++ }
            Operator.UNSAFE_CLOSE -> { popFrame(); pointer++ }
            Operator.NORMAL -> { if (live()) executeNormal(ins); pointer++ }
        }
    }

    private fun push(frame: StackFrame) {
        if (stack.size >= maxStackDepth) throw ScriptException("stack overflow — block nesting exceeded $maxStackDepth")
        stack.addFirst(frame)
    }

    private fun popFrame() { if (stack.isNotEmpty()) stack.removeFirst() }

    private fun handleIf(ins: Instruction.Invoke) {
        val cond = live() && ins.action.condition(ctx, ins.args)
        push(StackFrame(ins.action, ins.args, pointer + 1, conditionalFlag = cond, ifFlag = cond))
    }

    private fun handleElseIf(ins: Instruction.Invoke) {
        val f = stack.firstOrNull() ?: return
        if (f.elseFlag || f.ifFlag) {
            f.conditionalFlag = false // a branch already matched, or we're past `else`
        } else {
            val cond = parentLive() && ins.action.condition(ctx, ins.args)
            f.conditionalFlag = cond
            if (cond) f.ifFlag = true
        }
    }

    private fun handleElse() {
        val f = stack.firstOrNull() ?: return
        f.conditionalFlag = parentLive() && !f.ifFlag
        f.elseFlag = true
        f.ifFlag = true
    }

    private fun handleLoopOpen(ins: Instruction.Invoke) {
        val parentLive = live()
        val frame = StackFrame(ins.action, ins.args, bodyStart = pointer + 1, conditionalFlag = parentLive)
        if (parentLive) frame.conditionalFlag = ins.action.enter(ctx, frame, ins.args)
        push(frame)
    }

    private fun handleLoopClose(ins: Instruction.Invoke) {
        val f = stack.firstOrNull()
        if (f == null) { pointer++; return }
        val keepLooping = f.conditionalFlag && ins.action.loopBack(ctx, f, ins.args)
        if (keepLooping) {
            pointer = f.bodyStart // rewind, keep the frame, re-run the body
        } else {
            popFrame()
            pointer++
        }
    }

    private fun handleBreak() {
        for (f in stack) {
            if (f.opener.operator == Operator.LOOP_OPEN) { f.conditionalFlag = false; return }
        }
    }

    private fun executeNormal(ins: Instruction.Invoke) {
        val rv = ins.action.execute(ctx, ins.args)
        rv.remoteMessage?.let { ctx.output.chat(it) }
        rv.localMessage?.let { ctx.output.log(it) }

        val outVar = ins.outVar ?: return
        if (rv.isVoid) return
        when (rv) {
            is ReturnValue.ArrayResult -> {
                if (!rv.append) ctx.registry.clearArray(outVar)
                rv.values.forEach { ctx.registry.push(outVar, it) }
            }
            else -> ctx.registry.setVariable(outVar, rv.value())
        }
    }

    private fun emitChat(text: String) {
        val expanded = ctx.expand(text)
        for (line in expanded.split('|')) {
            if (line.isNotEmpty()) ctx.output.chat(line)
        }
    }
}

/** Concrete [ExecutionContext] wiring the registry, output sink, expander and evaluator together. */
class RuntimeContext(
    override val registry: VariableRegistry,
    override val output: OutputSink,
) : ExecutionContext {
    private val expander = VariableExpander(registry)
    private val evaluator = ExpressionEvaluator { name -> registry.getVariable(name) }

    override fun expand(text: String, quoteStrings: Boolean): String = expander.expand(text, quoteStrings)

    override fun evaluate(expression: String): Value {
        val expanded = expander.expand(expression, quoteStrings = true)
        return evaluator.evaluate(expanded)
    }

    override fun resolve(variableName: String): Value? = registry.getVariable(variableName)
}
