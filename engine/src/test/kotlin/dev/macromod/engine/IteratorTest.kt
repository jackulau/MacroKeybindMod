package dev.macromod.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class IteratorTest {

    @Test fun `foreach env iterates the set scalar variables`() {
        // two scalars are set, so the env iterator yields two values -> two iterations
        val out = runScript("#a := 1; &b := \"y\"; foreach(&v, env); log(\"got\"); next")
        assertEquals(2, out.logs.size)
    }

    @Test fun `foreach running iterates the (empty engine-side) task list`() {
        val out = runScript("foreach(&v, running); log(\"got\"); next")
        assertEquals(0, out.logs.size)
    }

    @Test fun `foreach still iterates an array`() {
        val out = runScript("push(&arr[], \"p\"); push(&arr[], \"q\"); foreach(&v, &arr[]); log(\"%&v%\"); next")
        assertEquals(listOf("p", "q"), out.logs)
    }
}
