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

    @Test fun `type joins multiple args with a space`() {
        // MKB ScriptActionType joins ALL params with a space before typing.
        assertEquals(listOf("a b c"), run("\$\${ type(\"a\", \"b\", \"c\") }\$\$").typed)
    }

    @Test fun `inventory scroll count is clamped mod 9 floored at 1`() {
        // MKB: count %= 9; if (count < 1) count = 1 — so 15 -> 6, and 0 -> 1.
        assertEquals(listOf(-6), run("\$\${ inventoryup(15) }\$\$").scrolls)
        assertEquals(listOf(1), run("\$\${ inventorydown(0) }\$\$").scrolls)
    }

    @Test fun `sprint off or zero releases instead of holding`() {
        // MKB: sprint(0) / sprint(off) -> actionSetSprinting(false) (stop).
        assertEquals(listOf("sprint"), run("\$\${ sprint(\"off\") }\$\$").released)
        assertEquals(listOf("sprint"), run("\$\${ sprint(\"0\") }\$\$").released)
        // a bare sprint (no arg) still holds
        assertEquals(listOf("sprint"), run("\$\${ sprint }\$\$").held)
    }

    @Test fun `look accepts cardinal direction keywords`() {
        // MKB ScriptActionLook: north=180, south=0, east=270, west=90 (MC yaw, pitch 0).
        assertEquals(listOf(180f to 0f), run("\$\${ look(\"north\") }\$\$").looks)
        assertEquals(listOf(270f to 0f), run("\$\${ look(\"east\") }\$\$").looks)
        // case-insensitive, and looks() shares the same resolution
        assertEquals(listOf(90f to 0f), run("\$\${ look(\"WEST\") }\$\$").looks)
        assertEquals(listOf(0f to 0f), run("\$\${ looks(\"south\") }\$\$").looks)
    }
}
