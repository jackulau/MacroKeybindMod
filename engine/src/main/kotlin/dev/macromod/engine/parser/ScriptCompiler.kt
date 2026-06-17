package dev.macromod.engine.parser

import dev.macromod.engine.action.ActionRegistry
import dev.macromod.engine.action.Args
import dev.macromod.engine.action.UnknownAction
import dev.macromod.engine.ast.Instruction
import dev.macromod.engine.variable.Variable

/**
 * Compiles macro source into a flat `List<Instruction>` (Phase B).
 *
 * Two entry points:
 *  - [compileMacro] — the bind format: literal chat text with `$${ … }$$` script islands.
 *  - [compileScript] — a pure script body (a `.txt` script file / the modern layer):
 *    every statement is script, newlines and `;` both separate statements.
 */
class ScriptCompiler(private val actions: ActionRegistry) {

    fun compileMacro(source: String): List<Instruction> {
        val out = ArrayList<Instruction>()
        val collapsed = source.replace("\$\${\$\${", "\$\${").replace("}\$\$}\$\$", "}\$\$")
        var from = 0
        while (true) {
            val match = SCRIPT_BLOCK.find(collapsed, from) ?: break
            if (match.range.first > from) emitChatText(collapsed.substring(from, match.range.first), out)
            parseStatements(match.groupValues[1], out)
            from = match.range.last + 1
        }
        if (from < collapsed.length) emitChatText(collapsed.substring(from), out)
        return out
    }

    fun compileScript(body: String): List<Instruction> {
        val out = ArrayList<Instruction>()
        parseStatements(body, out)
        return out
    }

    private fun emitChatText(text: String, out: MutableList<Instruction>) {
        for (line in text.split('\n')) {
            if (line.isNotEmpty()) out.add(Instruction.ChatLine(line))
        }
    }

    private fun parseStatements(body: String, out: MutableList<Instruction>) {
        val normalised = body.replace('\r', '\n').replace('\n', ';')
        for (stmt in splitTopLevel(normalised, ';')) {
            compileStatement(stmt)?.let { out.add(it) }
        }
    }

    private fun compileStatement(raw: String): Instruction.Invoke? {
        val stmt = raw.trim()
        if (stmt.isEmpty() || stmt.startsWith("//")) return null

        parseAssignment(stmt)?.let { return it }

        ACTION_CALL.matchEntire(stmt)?.let { return buildCall(it.groupValues[1], it.groupValues[2], null) }

        DIRECTIVE.matchEntire(stmt)?.let { return buildInvoke(it.groupValues[1], Args.EMPTY, "", null) }

        // Unrecognised statement → a no-op so the program stays well-formed.
        return buildInvoke(stmt.substringBefore('(').trim(), Args.EMPTY, "", null)
    }

    private fun parseAssignment(stmt: String): Instruction.Invoke? {
        val eq = findAssignmentEquals(stmt)
        if (eq <= 0) return null
        val isSet = stmt[eq - 1] == ':'
        val lhs = stmt.substring(0, if (isSet) eq - 1 else eq).trim()
        if (!Variable.isValid(lhs)) return null
        val rhs = stmt.substring(eq + 1).trim()

        if (!isSet) {
            val call = ACTION_CALL.matchEntire(rhs)
            if (call != null && actions.has(call.groupValues[1])) {
                return buildCall(call.groupValues[1], call.groupValues[2], outVar = lhs)
            }
        }
        val actionName = if (isSet) "set" else "assign"
        return buildInvoke(actionName, Args(listOf(lhs, rhs)), rhs, null)
    }

    /** Index of the `=` that means assignment (skips `==`, `!=`, `<=`, `>=`, and quoted text). */
    private fun findAssignmentEquals(s: String): Int {
        var inQuotes = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == '=' -> {
                    val prev = if (i > 0) s[i - 1] else ' '
                    val next = if (i + 1 < s.length) s[i + 1] else ' '
                    if (prev == '!' || prev == '<' || prev == '>' || prev == '=') { i++; continue }
                    if (next == '=') { i += 2; continue }
                    return i
                }
            }
            i++
        }
        return -1
    }

    private fun buildCall(name: String, argText: String, outVar: String?): Instruction.Invoke {
        val firstUnquoted = name.lowercase() in CONDITION_KEYWORDS
        return buildInvoke(name, Args(tokeniseArgs(argText, firstUnquoted)), argText, outVar)
    }

    private fun buildInvoke(name: String, args: Args, rawArgs: String, outVar: String?): Instruction.Invoke {
        val action = actions.get(name) ?: UnknownAction(name)
        return Instruction.Invoke(action, args, rawArgs, outVar)
    }

    private fun tokeniseArgs(text: String, firstUnquoted: Boolean): List<String> {
        if (text.isBlank()) return emptyList()
        return splitTopLevel(text, ',').mapIndexed { i, part ->
            val trimmed = part.trim()
            if (i == 0 && firstUnquoted) trimmed else dequote(trimmed)
        }
    }

    private fun dequote(s: String): String {
        if (s.length >= 2 && s.first() == '"' && s.last() == '"') {
            return s.substring(1, s.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return s
    }

    /** Split on [delim] outside double-quoted strings, honouring `\` escapes; the delimiter is dropped. */
    private fun splitTopLevel(text: String, delim: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length -> { sb.append(c).append(text[i + 1]); i += 2 }
                c == '"' -> { inQuotes = !inQuotes; sb.append(c); i++ }
                c == delim && !inQuotes -> { out.add(sb.toString()); sb.clear(); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        out.add(sb.toString())
        return out
    }

    companion object {
        private val SCRIPT_BLOCK = Regex("\\\$\\\$\\{(.*?)}\\\$\\\$", RegexOption.DOT_MATCHES_ALL)
        private val ACTION_CALL = Regex("^([a-zA-Z_]+)\\s*\\((.*)\\)$", RegexOption.DOT_MATCHES_ALL)
        private val DIRECTIVE = Regex("^([a-zA-Z_]+)$")
        private val CONDITION_KEYWORDS = setOf("if", "elseif", "iif", "while", "until")
    }
}
