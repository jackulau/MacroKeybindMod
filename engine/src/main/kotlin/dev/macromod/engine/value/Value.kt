package dev.macromod.engine.value

/**
 * A runtime value in the macro engine.
 *
 * The original Macro/Keybind Mod collapsed everything to `int` (booleans were 1/0,
 * strings were interned as descending integer ids). This reimplementation keeps a
 * small, properly typed value model — it is clearer, avoids the string-interning
 * hack, and makes string comparisons correct rather than identity-based.
 */
sealed interface Value {
    @JvmInline value class Num(val v: Int) : Value
    @JvmInline value class Str(val v: String) : Value
    @JvmInline value class Bool(val v: Boolean) : Value

    /** Truthiness — mirrors the original's permissive rules: nonzero int, the string "true", or boolean true. */
    fun asBoolean(): Boolean = when (this) {
        is Bool -> v
        is Num -> v != 0
        is Str -> v.equals("true", ignoreCase = true) || (v.toIntOrNull()?.let { it != 0 } ?: v.isNotEmpty())
    }

    /** Integer projection — booleans are 1/0, non-numeric strings are 0. */
    fun asInt(): Int = when (this) {
        is Num -> v
        is Bool -> if (v) 1 else 0
        is Str -> v.toIntOrNull() ?: 0
    }

    fun asString(): String = when (this) {
        is Num -> v.toString()
        is Bool -> if (v) "True" else "False"
        is Str -> v
    }

    companion object {
        val ZERO = Num(0)
        val TRUE = Bool(true)
        val FALSE = Bool(false)
        val EMPTY = Str("")

        /** Best-effort parse of a literal token into a typed value. */
        fun of(token: String): Value {
            token.toIntOrNull()?.let { return Num(it) }
            return when (token.lowercase()) {
                "true" -> TRUE
                "false" -> FALSE
                else -> Str(token)
            }
        }
    }
}
