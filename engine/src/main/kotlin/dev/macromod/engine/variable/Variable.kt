package dev.macromod.engine.variable

/** The three variable representations, selected by the name's sigil. */
enum class VarType { FLAG, COUNTER, STRING }

/**
 * A parsed variable reference.
 *
 * Grammar (case-insensitive), mirroring the original `Variable.variableNamePattern`:
 * `[@][#|&] name [index]` where
 *  - `@`  → shared/global (orthogonal to type)
 *  - `#`  → COUNTER (int),  `&` → STRING,  none → FLAG (bool)
 *  - name starts `[a-z~]` then `[a-z0-9_-]*`
 *  - optional `[0-9]{1,5}` array index; empty `[]` marks the whole-array specifier.
 */
data class Variable(
    val shared: Boolean,
    val type: VarType,
    val name: String,
    /** null = scalar; >=0 = explicit array element; -1 = whole-array specifier (`name[]`). */
    val index: Int?,
) {
    val isArrayElement: Boolean get() = index != null && index >= 0
    val isArraySpecifier: Boolean get() = index == -1

    /** Canonical key used for storage: sigil + name (no `@`, no index). */
    fun storageKey(): String = when (type) {
        VarType.COUNTER -> "#$name"
        VarType.STRING -> "&$name"
        VarType.FLAG -> name
    }

    companion object {
        private val PATTERN = Regex("^(@?)([#&]?)([a-z~][a-z0-9_\\-]*)(\\[([0-9]{0,5})])?$", RegexOption.IGNORE_CASE)

        fun parse(raw: String): Variable? {
            val m = PATTERN.matchEntire(raw.trim()) ?: return null
            val shared = m.groupValues[1] == "@"
            val type = when (m.groupValues[2]) {
                "#" -> VarType.COUNTER
                "&" -> VarType.STRING
                else -> VarType.FLAG
            }
            val name = m.groupValues[3].lowercase()
            val hasBrackets = m.groupValues[4].isNotEmpty()
            val idxStr = m.groupValues[5]
            val index = when {
                !hasBrackets -> null
                idxStr.isEmpty() -> -1            // name[]  → whole-array specifier
                else -> idxStr.toInt()
            }
            return Variable(shared, type, name, index)
        }

        fun isValid(raw: String): Boolean = parse(raw) != null
    }
}
