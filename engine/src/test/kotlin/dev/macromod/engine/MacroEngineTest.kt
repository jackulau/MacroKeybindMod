package dev.macromod.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class MacroEngineTest {
    @Test
    fun `engine exposes a version`() {
        assertEquals("0.1.0", MacroEngine.VERSION)
    }
}
