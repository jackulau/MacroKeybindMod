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

    @Test fun `clearchat clears the stream`() {
        assertEquals(1, runScript("clearchat").clears)
    }

    @Test fun `selectchannel records the channel`() {
        assertEquals(listOf("mychan"), runScript("selectchannel(\"mychan\")").channels)
    }
}
