package dev.macromod.engine.action

import dev.macromod.engine.runScript
import kotlin.test.Test
import kotlin.test.assertEquals

class OutputActionTest {

    @Test fun `lograw records the raw line`() {
        assertEquals(listOf("rawline"), runScript("lograw(\"rawline\")").raws)
    }

    @Test fun `logto records target and text`() {
        assertEquals(listOf("file" to "line"), runScript("logto(\"file\", \"line\")").tos)
    }

    @Test fun `logto converts amp codes for non-file targets but not for txt files`() {
        // MKB ScriptActionLogTo: a non-.txt target is colour-converted; a .txt target writes the raw line.
        assertEquals(listOf("area" to "§ahi"), runScript("logto(\"area\", \"&ahi\")").tos)
        assertEquals(listOf("out.txt" to "&ahi"), runScript("logto(\"out.txt\", \"&ahi\")").tos)
    }

    @Test fun `clearchat clears the stream`() {
        assertEquals(1, runScript("clearchat").clears)
    }

    @Test fun `selectchannel records the channel`() {
        assertEquals(listOf("mychan"), runScript("selectchannel(\"mychan\")").channels)
    }
}
