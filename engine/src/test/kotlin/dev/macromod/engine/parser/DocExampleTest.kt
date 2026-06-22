package dev.macromod.engine.parser

import dev.macromod.engine.ScriptHost
import dev.macromod.engine.action.UnknownAction
import dev.macromod.engine.ast.Instruction
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Compiles every ` ```macro ` example embedded in the user-facing docs and asserts each
 * statement resolves to a real action — that none silently degrades to an [UnknownAction]
 * no-op (the [ScriptCompiler.compileStatement] fallback that keeps any input well-formed).
 *
 * Why this matters: because the compiler never throws on bad input, a documented example
 * that drifts out of sync with the engine grammar would not fail any existing test — it
 * would just quietly compile to no-ops and mislead users. This test makes that drift loud.
 *
 * It reads the repo `docs/` tree at test time (walking up from the working directory). The
 * engine module is also consumable standalone (JIJ-shaded), so if `docs/` is absent the
 * test no-ops instead of failing.
 */
class DocExampleTest {
    private val host = ScriptHost()

    /** Walk up from the working dir to find the repo `docs/` tree. Null if checked out standalone. */
    private fun docsDir(): File? {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs")
            if (File(candidate, "DSL-REFERENCE.md").exists()) return candidate
            dir = dir.parentFile
        }
        return null
    }

    /** Extract the bodies of ` ```macro ` fenced blocks, in source order. */
    private fun macroBlocks(markdown: String): List<String> {
        val blocks = ArrayList<String>()
        val body = StringBuilder()
        var inBlock = false
        for (line in markdown.lines()) {
            if (!inBlock && line.trimEnd() == "```macro") { inBlock = true; body.setLength(0); continue }
            if (inBlock && line.trimStart().startsWith("```")) { inBlock = false; blocks.add(body.toString()); continue }
            if (inBlock) body.append(line).append('\n')
        }
        return blocks
    }

    /** Route each block through its intended entry point: islands -> bind format, else modern brace syntax. */
    private fun compileBlock(src: String): List<Instruction> =
        if (src.contains("\$\${")) host.compile(src).program
        else host.compileModern(src).program

    private fun unresolvedActions(program: List<Instruction>): List<String> =
        program.filterIsInstance<Instruction.Invoke>()
            .filter { it.action is UnknownAction }
            .map { it.action.name }
            .distinct()

    @Test
    fun `every macro example in the docs compiles to real actions`() {
        val docs = docsDir() ?: return // standalone checkout: nothing to validate
        val failures = ArrayList<String>()
        var examples = 0
        docs.walkTopDown().filter { it.isFile && it.extension == "md" }.forEach { md ->
            macroBlocks(md.readText()).forEachIndexed { index, block ->
                examples++
                val unresolved = unresolvedActions(compileBlock(block))
                if (unresolved.isNotEmpty()) {
                    failures.add("${md.name} macro#${index + 1}: unresolved $unresolved in:\n${block.trimEnd()}")
                }
            }
        }
        assertTrue(examples > 0, "found no `macro` examples under $docs — extraction likely broken")
        assertTrue(
            failures.isEmpty(),
            "doc macro examples leaked UnknownAction (drifted from the engine grammar):\n\n" +
                failures.joinToString("\n---\n"),
        )
    }
}
