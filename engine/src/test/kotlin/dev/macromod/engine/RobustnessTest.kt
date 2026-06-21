package dev.macromod.engine

import dev.macromod.engine.runtime.ScriptException
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Robustness: a user can type ANY text as a macro, so the engine must degrade gracefully on
 * garbage — either run/no-op, or throw the engine's OWN controlled [ScriptException] (which the
 * host catches and reports). An *uncontrolled* throw (NPE / IndexOutOfBounds / NumberFormat /
 * StackOverflowError) leaking out of [ScriptHost.run] is a robustness bug: it means a malformed
 * macro can surface a raw JVM error instead of a clean "your script is bad" signal.
 *
 * This battery feeds adversarial inputs and asserts none throw anything other than ScriptException.
 */
class RobustnessTest {
    // (label, source) pairs. `$$` islands are escaped as \$\$ for Kotlin.
    private val cases: List<Pair<String, String>> = listOf(
        // --- unbalanced control flow (legacy) ---
        "if no endif" to "\$\${ if(1); log(\"x\") }\$\$",
        "endif alone" to "\$\${ endif }\$\$",
        "else alone" to "\$\${ else; log(\"x\") }\$\$",
        "elseif alone" to "\$\${ elseif(1); log(\"x\") }\$\$",
        "do no loop" to "\$\${ do; log(\"x\") }\$\$",
        "loop alone" to "\$\${ loop }\$\$",
        "next alone" to "\$\${ next }\$\$",
        "break alone" to "\$\${ break }\$\$",
        "while empty" to "\$\${ while(); log(\"x\"); loop }\$\$",
        "for empty" to "\$\${ for(); log(\"x\"); next }\$\$",
        "foreach empty" to "\$\${ foreach(); log(\"x\"); next }\$\$",
        "unsafe no close" to "\$\${ unsafe; log(\"x\") }\$\$",
        // --- unbalanced braces (modern) ---
        "open brace alone" to "if 1 {",
        "close brace alone" to "}",
        "extra close brace" to "if 1 { } }",
        "nested unclosed" to "if 1 { if 2 { log(\"x\")",
        "repeat no count" to "repeat { log(\"x\") }",
        // --- malformed action args ---
        "substr no args" to "\$\${ &r = substr() }\$\$",
        "substr one arg" to "\$\${ &r = substr(\"hi\") }\$\$",
        "min no args" to "\$\${ #r = min() }\$\$",
        "max one arg" to "\$\${ #r = max(5) }\$\$",
        "abs no args" to "\$\${ #r = abs() }\$\$",
        "abs non-numeric" to "\$\${ #r = abs(\"notanumber\") }\$\$",
        "sqrt non-numeric" to "\$\${ #r = sqrt(\"xyz\") }\$\$",
        "getid no args" to "\$\${ &r = getid() }\$\$",
        "getidrel no args" to "\$\${ &r = getidrel() }\$\$",
        "slot no args" to "\$\${ slot() }\$\$",
        "slot huge" to "\$\${ slot(99999999999) }\$\$",
        "random reversed" to "\$\${ #r = random(100, 1) }\$\$",
        "split no args" to "\$\${ &r[] = split() }\$\$",
        "join no array" to "\$\${ &r = join(\",\") }\$\$",
        "match bad regex" to "\$\${ &r = match(\"x\", \"[\") }\$\$",
        "regexreplace bad regex" to "\$\${ &r = regexreplace(\"x\", \"(\", \"y\") }\$\$",
        // --- garbage expressions ---
        "empty assign" to "\$\${ #x = }\$\$",
        "operators only" to "\$\${ #x = +++ }\$\$",
        "div by zero" to "\$\${ #x = 5 / 0 }\$\$",
        "mod by zero" to "\$\${ #x = 5 % 0 }\$\$",
        "unbalanced open parens" to "\$\${ #x = ((((1 }\$\$",
        "unbalanced close parens" to "\$\${ #x = 1)))) }\$\$",
        "bare percent" to "\$\${ #x = % }\$\$",
        "unclosed var ref" to "\$\${ #x = %UNCLOSED }\$\$",
        "nonexistent var" to "\$\${ #x = %DOESNOTEXIST% }\$\$",
        "huge number" to "\$\${ #x = 999999999999999999999 }\$\$",
        "expr garbage" to "\$\${ #x = 1 ++ ** // 2 }\$\$",
        // --- malformed \$\$ delimiters / param sigils ---
        "bare island" to "\$\$",
        "open island only" to "\$\${",
        "close island only" to "}\$\$",
        "nested island" to "\$\${ \$\${ log(\"x\") }\$\$ }\$\$",
        "double percent" to "\$\${ log(\"%%\") }\$\$",
        "lone param sigil" to "\$\${ log(\"\$\$\") }\$\$",
        "open param brace" to "\$\${ log(\"\$\${\") }\$\$",
        // --- empty / whitespace / comments ---
        "empty string" to "",
        "just semicolons" to "\$\${ ;;;;; }\$\$",
        "just newlines" to "\n\n\n",
        "only comment" to "\$\${ // just a comment }\$\$",
        "only whitespace" to "    \t   ",
        // --- weird variable names ---
        "assign to bare hash" to "\$\${ # = 1 }\$\$",
        "assign to bare amp" to "\$\${ & = 1 }\$\$",
        "assign to empty array" to "\$\${ &[] = 1 }\$\$",
        "key underscore empty" to "\$\${ #x = %KEY_% }\$\$",
        // --- pathological size / depth (must hit a controlled guard, not SOE/hang) ---
        "deep block nesting" to "\$\${ " + "if(1);".repeat(500) + "log(\"x\");" + "endif;".repeat(500) + " }\$\$",
        "deep paren expr" to "\$\${ #x = " + "(".repeat(2000) + "1" + ")".repeat(2000) + " }\$\$",
        "long string literal" to "\$\${ log(\"" + "A".repeat(50000) + "\") }\$\$",
        "many statements" to "\$\${ " + "pass;".repeat(5000) + " }\$\$",
        "deeply nested modern" to "if 1 { ".repeat(100) + "log(\"x\")" + " }".repeat(100),
        // --- runaway loops: must throw the CONTROLLED ScriptException (step cap), not hang ---
        "infinite legacy loop" to "\$\${ do; pass; loop }\$\$",
        "infinite modern loop" to "forever { pass }",
        "infinite goto" to "\$\${ label start; goto(start) }\$\$",
    )

    @Test fun `adversarial input never throws an uncontrolled exception`() {
        val bad = mutableListOf<String>()
        for ((label, src) in cases) {
            try {
                ScriptHost().run(src)
            } catch (e: ScriptException) {
                // controlled, host-catchable signal — this is the graceful failure mode, fine.
            } catch (e: Throwable) {
                bad.add("[$label] ${e::class.simpleName}: ${e.message?.take(100)}")
            }
        }
        assertTrue(
            bad.isEmpty(),
            "malformed macros leaked an uncontrolled exception (should degrade or throw ScriptException):\n" +
                bad.joinToString("\n"),
        )
    }
}
