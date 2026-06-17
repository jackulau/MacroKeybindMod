package dev.macromod.engine.parser

/**
 * The modern, brace-structured front-end for the DSL. It transpiles a clean
 * block syntax into the legacy statement form, which the existing [ScriptCompiler]
 * then compiles — so there is exactly one runtime, and legacy + modern scripts are
 * fully interoperable.
 *
 * Supported constructs (K&R braces; `}` / `} else {` / `} elseif cond {` on their own line):
 * ```
 * if <cond> { … } elseif <cond> { … } else { … }
 * while <cond> { … }          // pre-check loop
 * repeat <count> { … }        // counted loop
 * forever { … }               // infinite loop (until break)
 * foreach <var> in <array> { … }
 * <action(args)>  /  <var> = <expr>   // plain statements, identical to legacy
 * ```
 */
class ModernTranspiler {

    fun transpile(source: String): String {
        val out = StringBuilder()
        val closers = ArrayDeque<String>()
        var loopCounter = 0

        for (rawLine in source.lines()) {
            val line = rawLine.trim().removeSuffix(";").trim()
            if (line.isEmpty() || line.startsWith("//")) continue

            when {
                line == "}" -> out.append(closers.removeFirstOrNull() ?: "")

                ELSE_BRACE.matches(line) -> out.append("else;")

                ELSEIF_BRACE.matchEntire(line)?.let { out.append("elseif(${it.groupValues[1].trim()});") } != null -> Unit

                line.endsWith("{") -> {
                    val header = line.dropLast(1).trim()
                    loopCounter = openBlock(header, out, closers, loopCounter)
                }

                else -> out.append("$line;")
            }
        }
        return out.toString()
    }

    private fun openBlock(header: String, out: StringBuilder, closers: ArrayDeque<String>, loopCounter: Int): Int {
        var counter = loopCounter
        when {
            header.startsWith("if ") -> {
                out.append("if(${header.removePrefix("if ").trim()});")
                closers.addFirst("endif;")
            }
            header.startsWith("while ") -> {
                val cond = header.removePrefix("while ").trim()
                out.append("do;if(!($cond));break;endif;") // pre-check: bail before the body when false
                closers.addFirst("loop;")
            }
            header.startsWith("repeat ") -> {
                val count = header.removePrefix("repeat ").trim()
                out.append("for(#__loop${counter++}, 1, $count);")
                closers.addFirst("next;")
            }
            header == "forever" -> {
                out.append("do;")
                closers.addFirst("loop;")
            }
            header.startsWith("foreach ") -> {
                val m = FOREACH.matchEntire(header)
                if (m != null) {
                    out.append("foreach(${m.groupValues[1].trim()}, ${m.groupValues[2].trim()});")
                    closers.addFirst("next;")
                } else {
                    out.append("$header;")
                    closers.addFirst("")
                }
            }
            else -> {
                // Not a recognised block header — treat as a plain statement that happened to end in `{`.
                out.append("$header;")
                closers.addFirst("")
            }
        }
        return counter
    }

    companion object {
        private val ELSE_BRACE = Regex("^}\\s*else\\s*\\{$")
        // `else\s*if` matches both `else if` and `elseif`.
        private val ELSEIF_BRACE = Regex("^}\\s*else\\s*if\\s+(.+?)\\s*\\{$")
        private val FOREACH = Regex("^foreach\\s+(\\S+)\\s+in\\s+(\\S+)$")
    }
}
