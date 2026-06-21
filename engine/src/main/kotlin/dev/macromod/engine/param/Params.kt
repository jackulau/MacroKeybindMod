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
    LIST,          // $$[label[opts]hint] (incl. the $$[[a,b,c]] empty-label form) — pick from a list
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
        // Fast path: skip ALL substitution when the source has no real param trigger and no `\`.
        // process() runs on EVERY ScriptHost.compile() (including programCache HITS, to compute the
        // post-substitution key), so a param-free macro fired up to 20x/s must not allocate. The old
        // guard was the coarse `!contains("$$")`, but the modern brace syntax `$${ ... }$$` ALWAYS
        // holds `$$` in its block delimiters while carrying no param, so it defeated the guard and
        // paid five escaping `Matcher`s (~1048 B/call measured) for a no-op on the bread-and-butter
        // onTick path. hasSubstitution treats a `$$` as a param only when a real trigger char follows,
        // so the delimiter-only case returns unchanged (0 B). The `%var%` path has the same guard.
        if (!hasSubstitution(source)) return source
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

    /**
     * True if [source] needs any substitution pass: it holds a `\` (unescape) or a `$$` immediately
     * followed by a real param trigger char. The trigger set is the UNION of the six `$$` passes'
     * leading chars: `!` (stop), `[` (list/named), `<` (include), a digit (presets), and one of
     * `? p i d f u w t h k m s` (simple codes). A `$$` followed by anything else (notably the `{`/`}`
     * of a `$${ }$$` block delimiter) is not a param, so it triggers nothing and is skipped. This is a
     * conservative SUPERSET: it never returns false when a pass would match (that would silently drop
     * a real substitution), and ParamSubstitutorTest's per-form cases pin the trigger set against drift.
     */
    private fun hasSubstitution(source: String): Boolean {
        if (source.indexOf('\\') >= 0) return true
        var i = source.indexOf("\$\$")
        while (i >= 0) {
            val c = if (i + 2 < source.length) source[i + 2] else ' '
            if (c == '!' || c == '[' || c == '<' || c in '0'..'9' ||
                c == '?' || c == 'p' || c == 'i' || c == 'd' || c == 'f' || c == 'u' ||
                c == 'w' || c == 't' || c == 'h' || c == 'k' || c == 'm' || c == 's'
            ) return true
            i = source.indexOf("\$\$", i + 1) // +1 not +2: catch an overlapping `$$` (e.g. `$$$0`)
        }
        return false
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
            // group[1] = label, group[2] = options, group[3] = type hint. Route the options (the choice
            // set) to the resolver; with no resolver, default to the first option (the documented contract).
            if (isEscaped(s, m.range.first)) m.value
            else resolver.resolve(ParamCode.LIST, m.groupValues[2]) ?: m.groupValues[2].substringBefore(",").trim()
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
        // MKB MacroParamProviderList `$$[label[opts]hint]`: optional label, the options, then an
        // `[iu]?(:d)?` type hint. The empty-label case `$$[[a,b,c]]` (label="") still matches, so the
        // old double-bracket form keeps working; the labeled `$$[pick[a,b,c]]` form no longer leaks.
        private val LIST = Regex("\\$\\$\\[([a-zA-Z0-9 _\\-.]*)\\[([^\\]\\[$|]+)]([iu]?(?::d)?)]")
        private val INCLUDE = Regex("\\$\\$<([^>]+)>")
        // Longer alternatives FIRST (alternation is ordered): `px/py/pz/pn` before `p`, `i:d` before `i`,
        // so a place sub-code / combined item:damage is taken whole instead of leaving a stray suffix.
        private val SIMPLE = Regex("\\$\\$(\\?|px|py|pz|pn|p|i:d|i|d|f|u|t|w|h|k|m|s)")
    }
}
