package dev.macromod.engine.param

import dev.macromod.engine.value.Value
import dev.macromod.engine.variable.VarType
import dev.macromod.engine.variable.Variable
import dev.macromod.engine.variable.VariableRegistry

/**
 * Runtime `%var%` expansion (distinct from compile-time `$$` substitution).
 *
 * `%name%` references are replaced with the variable's current value. Unresolved
 * references fall back to a type-appropriate default ("0" / "" / "False"). With
 * [quoteStrings] set, string values are wrapped in quotes — used before feeding text
 * into the expression evaluator so string literals stay distinguishable from numbers.
 */
class VariableExpander(private val registry: VariableRegistry) {

    fun expand(text: String, quoteStrings: Boolean = false): String {
        if (text.indexOf('%') < 0) return text
        // Each pass replaces EVERY `%var%` in one left-to-right scan (a single StringBuilder via
        // Regex.replace) instead of the old find-one / rebuild-the-whole-string / rescan-from-0
        // loop, which was O(N^2) on a line with many variables. We still loop to a fixpoint so a
        // variable whose value contains another `%ref%` expands transitively (the historical
        // behaviour); `next == result` detects that nothing changed and also short-circuits a
        // self-referential cycle (`%a%` -> "%a%") immediately instead of spinning to the cap.
        var result = text
        var passes = 0
        while (passes++ < MAX_ITERATIONS) {
            if (result.indexOf('%') < 0) break
            val next = PATTERN.replace(result) { m ->
                val name = m.groupValues[1]
                render(name, registry.getVariable(name), quoteStrings)
            }
            if (next == result) break
            result = next
        }
        return result
    }

    private fun render(name: String, value: Value?, quote: Boolean): String {
        if (value != null) {
            return if (value is Value.Str && quote) "\"${value.v}\"" else value.asString()
        }
        return when (Variable.parse(name)?.type) {
            VarType.COUNTER -> "0"
            VarType.STRING -> if (quote) "\"\"" else ""
            VarType.FLAG -> "False"
            null -> ""
        }
    }

    companion object {
        private const val MAX_ITERATIONS = 256
        // Index cap is 9 digits to match Variable.parse: the write path stores a[100000+], so a
        // `%a[100000]%` read reference must match here too (5 digits silently left it un-expanded).
        private val PATTERN = Regex("%(@?[#&]?[a-zA-Z~][a-zA-Z0-9_\\-]*(?:\\[[0-9]{1,9}])?)%")
    }
}

/** The interactive `$$` parameter codes resolved by the host (prompts, pickers). */
enum class ParamCode {
    PROMPT,        // $$?
    NAMED,         // $$[name]
    ITEM,          // $$i  /  $$i:d (label ":d" → identifier:damage)
    ITEM_DAMAGE,   // $$d
    FRIEND,        // $$f
    USER,          // $$u
    TOWN,          // $$t
    WARP,          // $$w
    HOME,          // $$h
    INCLUDE,       // $$<file>  — splice a script file's contents
    LIST,          // $$[[a,b,c]] — pick from a literal list
    RESOURCEPACK,  // $$k
    SCRIPT,        // $$m
    PLACE,         // $$p / $$px / $$py / $$pz / $$pn  (label "x"/"y"/"z"/"n" picks the component; null = formatted coords)
    SHADER,        // $$s
}

/**
 * Resolves interactive `$$` codes. The pure engine has no UI, so the default returns
 * null (→ empty substitution); the Fabric host supplies prompts/pickers.
 */
fun interface ParamResolver {
    fun resolve(code: ParamCode, label: String?): String?

    companion object {
        val NONE = ParamResolver { _, _ -> null }
    }
}

/**
 * Phase A of the pipeline — compile-time `$$` parameter substitution. Implemented
 * incrementally; see deliverable D8 for the full code table. Preset positional args
 * (`$$0`–`$$9`) and named/interactive codes route through [resolver].
 */
class ParamSubstitutor(
    private val resolver: ParamResolver = ParamResolver.NONE,
    private val presets: List<String> = emptyList(),
) {
    fun process(source: String): String {
        var s = source
        s = substituteStop(s)        // $$!  truncates the macro here
        s = substitutePresets(s)
        s = substituteList(s)        // $$[[a,b,c]]  (before NAMED — different bracket shape)
        s = substituteNamed(s)
        s = substituteInclude(s)     // $$<file>
        s = substituteSimpleCodes(s)
        s = unescape(s)
        return s
    }

    /** `$$!` truncates the script at that point (the rest is dropped). */
    private fun substituteStop(s: String): String {
        var from = 0
        while (true) {
            val idx = s.indexOf("\$\$!", from)
            if (idx < 0) return s
            if (!isEscaped(s, idx)) return s.substring(0, idx)
            from = idx + 3
        }
    }

    private fun substituteList(s: String): String =
        LIST.replace(s) { m ->
            if (isEscaped(s, m.range.first)) m.value
            else resolver.resolve(ParamCode.LIST, m.groupValues[1]) ?: m.groupValues[1].substringBefore(",").trim()
        }

    private fun substituteInclude(s: String): String =
        INCLUDE.replace(s) { m ->
            if (isEscaped(s, m.range.first)) m.value
            else resolver.resolve(ParamCode.INCLUDE, m.groupValues[1]) ?: ""
        }

    private fun substitutePresets(s: String): String =
        PRESET.replace(s) { m ->
            if (isEscaped(s, m.range.first)) m.value
            else presets.getOrElse(m.groupValues[1].toInt()) { "" }
        }

    private fun substituteNamed(s: String): String =
        NAMED.replace(s) { m ->
            if (isEscaped(s, m.range.first)) m.value
            else resolver.resolve(ParamCode.NAMED, m.groupValues[1]) ?: ""
        }

    private fun substituteSimpleCodes(s: String): String =
        SIMPLE.replace(s) { m ->
            if (isEscaped(s, m.range.first)) return@replace m.value
            val resolved = when (m.groupValues[1]) {
                "?" -> resolver.resolve(ParamCode.PROMPT, null)
                "i" -> resolver.resolve(ParamCode.ITEM, null)
                "i:d" -> resolver.resolve(ParamCode.ITEM, ":d")   // combined identifier:damage
                "d" -> resolver.resolve(ParamCode.ITEM_DAMAGE, null)
                "f" -> resolver.resolve(ParamCode.FRIEND, null)
                "u" -> resolver.resolve(ParamCode.USER, null)
                "t" -> resolver.resolve(ParamCode.TOWN, null)
                "w" -> resolver.resolve(ParamCode.WARP, null)
                "h" -> resolver.resolve(ParamCode.HOME, null)
                "k" -> resolver.resolve(ParamCode.RESOURCEPACK, null)
                "m" -> resolver.resolve(ParamCode.SCRIPT, null)
                "p" -> resolver.resolve(ParamCode.PLACE, null)    // formatted "x y z"
                "px" -> resolver.resolve(ParamCode.PLACE, "x")
                "py" -> resolver.resolve(ParamCode.PLACE, "y")
                "pz" -> resolver.resolve(ParamCode.PLACE, "z")
                "pn" -> resolver.resolve(ParamCode.PLACE, "n")    // place name
                "s" -> resolver.resolve(ParamCode.SHADER, null)
                else -> null
            }
            resolved ?: ""
        }

    private fun isEscaped(s: String, at: Int): Boolean = at > 0 && s[at - 1] == '\\'

    private fun unescape(s: String): String = s.replace("\\$$", "$$").replace("\\|", "|")

    companion object {
        private val PRESET = Regex("\\$\\$([0-9])")
        private val NAMED = Regex("\\$\\$\\[([a-zA-Z0-9]{1,32})]")
        private val LIST = Regex("\\$\\$\\[\\[([^\\]]+)]]")
        private val INCLUDE = Regex("\\$\\$<([^>]+)>")
        // Longer alternatives FIRST (alternation is ordered): `px/py/pz/pn` before `p`, `i:d` before `i`,
        // so a place sub-code / combined item:damage is taken whole instead of leaving a stray suffix.
        private val SIMPLE = Regex("\\$\\$(\\?|px|py|pz|pn|p|i:d|i|d|f|u|t|w|h|k|m|s)")
    }
}
