package dev.macromod.engine.action

import dev.macromod.engine.ScriptHost
import kotlin.test.Test
import kotlin.test.assertEquals

class InputActionTest {
    private class RecordingInput : InputController {
        val taps = mutableListOf<String>()
        val held = mutableListOf<String>()
        val released = mutableListOf<String>()
        val looks = mutableListOf<Pair<Float, Float>>()
        val turns = mutableListOf<Pair<Float, Float>>()
        val slots = mutableListOf<Int>()
        val scrolls = mutableListOf<Int>()
        val typed = mutableListOf<String>()
        val toggled = mutableListOf<String>()
        override fun tap(key: String) { taps.add(key) }
        override fun hold(key: String) { held.add(key) }
        override fun release(key: String) { released.add(key) }
        override fun look(yaw: Float, pitch: Float) { looks.add(yaw to pitch) }
        override fun turn(deltaYaw: Float, deltaPitch: Float) { turns.add(deltaYaw to deltaPitch) }
        override fun slot(index: Int) { slots.add(index) }
        override fun scrollHotbar(delta: Int) { scrolls.add(delta) }
        override fun type(text: String) { typed.add(text) }
        override fun toggleKey(key: String) { toggled.add(key) }
    }

    private fun run(script: String): RecordingInput {
        val input = RecordingInput()
        ScriptHost().run(script, OutputSink.NOOP, input = input)
        return input
    }

    @Test fun `key and press tap`() {
        assertEquals(listOf("attack"), run("\$\${ key(\"attack\") }\$\$").taps)
        assertEquals(listOf("use"), run("\$\${ press(\"use\") }\$\$").taps)
    }

    @Test fun `keydown holds and keyup releases`() {
        val input = run("\$\${ keydown(\"forward\"); keyup(\"forward\") }\$\$")
        assertEquals(listOf("forward"), input.held)
        assertEquals(listOf("forward"), input.released)
    }

    @Test fun `look sets absolute rotation`() {
        assertEquals(listOf(90f to -30f), run("\$\${ look(90, -30) }\$\$").looks)
    }

    @Test fun `look works in a loop with variable expansion`() {
        val input = run("\$\${ for(#a, 0, 90, 45); look(\"%#a%\", 0); next }\$\$")
        assertEquals(listOf(0f to 0f, 45f to 0f, 90f to 0f), input.looks)
    }

    @Test fun `turn applies a delta`() {
        assertEquals(listOf(10f to 0f), run("\$\${ turn(10, 0) }\$\$").turns)
    }

    @Test fun `sprint holds and unsprint releases the sprint key`() {
        val input = run("\$\${ sprint; unsprint }\$\$")
        assertEquals(listOf("sprint"), input.held)
        assertEquals(listOf("sprint"), input.released)
    }

    @Test fun `slot selects a hotbar index`() {
        assertEquals(listOf(3), run("\$\${ slot(3) }\$\$").slots)
    }

    @Test fun `inventoryup and inventorydown scroll the hotbar`() {
        val input = run("\$\${ inventoryup; inventorydown(2) }\$\$")
        assertEquals(listOf(-1, 2), input.scrolls)
    }

    @Test fun `type injects text and togglekey flips a binding`() {
        val input = run("\$\${ type(\"hello\"); togglekey(\"sneak\") }\$\$")
        assertEquals(listOf("hello"), input.typed)
        assertEquals(listOf("sneak"), input.toggled)
    }
}
