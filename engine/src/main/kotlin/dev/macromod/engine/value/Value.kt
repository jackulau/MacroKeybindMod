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

    /**
     * Truthiness — matches the original engine: a value is true iff it is boolean true, a
     * nonzero int, or a string equal to "true" (ci) / parsing to a nonzero int. A non-numeric
     * string (incl. "false", "no", "") is FALSE — so a `&str` holding "false" used bare in a
     * condition reads false, as it should.
     */
    fun asBoolean(): Boolean = when (this) {
        is Bool -> v
        is Num -> v != 0
        is Str -> v.equals("true", ignoreCase = true) || (v.toIntOrNull()?.let { it != 0 } ?: false)
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
