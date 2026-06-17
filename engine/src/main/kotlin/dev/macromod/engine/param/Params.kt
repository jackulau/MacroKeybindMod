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
        var result = text
        var iterations = 0
        while (iterations++ < MAX_ITERATIONS) {
            val m = PATTERN.find(result) ?: break
            val name = m.groupValues[1]
            val replacement = render(name, registry.getVariable(name), quoteStrings)
            result = result.substring(0, m.range.first) + replacement + result.substring(m.range.last + 1)
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
        private val PATTERN = Regex("%(@?[#&]?[a-zA-Z~][a-zA-Z0-9_\\-]*(?:\\[[0-9]{1,5}])?)%")
    }
}

/** The interactive `$$` parameter codes resolved by the host (prompts, pickers). */
enum class ParamCode {
    PROMPT,        // $$?
    NAMED,         // $$[name]
    ITEM,          // $$i
    ITEM_DAMAGE,   // $$d / $$i:d
    FRIEND,        // $$f
    USER,          // $$u
    TOWN,          // $$t
    WARP,          // $$w
    HOME,          // $$h
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
        s = substitutePresets(s)
        s = substituteNamed(s)
        s = substituteSimpleCodes(s)
        s = unescape(s)
        return s
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
                "d" -> resolver.resolve(ParamCode.ITEM_DAMAGE, null)
                "f" -> resolver.resolve(ParamCode.FRIEND, null)
                "u" -> resolver.resolve(ParamCode.USER, null)
                "t" -> resolver.resolve(ParamCode.TOWN, null)
                "w" -> resolver.resolve(ParamCode.WARP, null)
                "h" -> resolver.resolve(ParamCode.HOME, null)
                else -> null
            }
            resolved ?: ""
        }

    private fun isEscaped(s: String, at: Int): Boolean = at > 0 && s[at - 1] == '\\'

    private fun unescape(s: String): String = s.replace("\\$$", "$$").replace("\\|", "|")

    companion object {
        private val PRESET = Regex("\\$\\$([0-9])")
        private val NAMED = Regex("\\$\\$\\[([a-zA-Z0-9]{1,32})]")
        private val SIMPLE = Regex("\\$\\$(\\?|i|d|f|u|t|w|h)")
    }
}
