package dev.macromod.engine.ast

import dev.macromod.engine.action.Args
import dev.macromod.engine.action.ScriptAction

/**
 * A compiled instruction. A macro compiles to a flat `List<Instruction>`; control-flow
 * nesting is reconstructed at runtime by the interpreter's pointer + operator stack
 * (it is not structural in the list — exactly as the original engine worked).
 */
sealed interface Instruction {
    /** Literal text sent to chat / typed as a command (may contain `%var%` and `|` line splits). */
    data class ChatLine(val text: String) : Instruction

    /** An action invocation with its tokenised args, the raw argument string, and an optional capture variable. */
    data class Invoke(
        val action: ScriptAction,
        val args: Args,
        val rawArgs: String,
        val outVar: String?,
    ) : Instruction
}
