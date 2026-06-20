package dev.macromod.engine.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventPayloadsTest {
    @Test fun `pickupDelta returns the id with the largest count increase`() {
        assertEquals(
            "minecraft:dirt" to 3,
            EventPayloads.pickupDelta(
                mapOf("minecraft:dirt" to 1, "minecraft:stone" to 5),
                mapOf("minecraft:dirt" to 4, "minecraft:stone" to 6),
            ),
        )
    }

    @Test fun `pickupDelta counts an item that appeared from nothing`() {
        assertEquals(
            "minecraft:diamond" to 2,
            EventPayloads.pickupDelta(emptyMap(), mapOf("minecraft:diamond" to 2)),
        )
    }

    @Test fun `pickupDelta is null when nothing rose`() {
        assertNull(EventPayloads.pickupDelta(mapOf("a" to 5), mapOf("a" to 5)))
        assertNull(EventPayloads.pickupDelta(mapOf("a" to 5), mapOf("a" to 3))) // a drop, not a pickup
        assertNull(EventPayloads.pickupDelta(emptyMap(), emptyMap()))
    }

    @Test fun `newJoiner finds the name that was added`() {
        assertEquals("Bob", EventPayloads.newJoiner(setOf("Alice"), setOf("Alice", "Bob")))
    }

    @Test fun `newJoiner is null when no one joined`() {
        assertNull(EventPayloads.newJoiner(setOf("Alice", "Bob"), setOf("Alice", "Bob")))
        assertNull(EventPayloads.newJoiner(setOf("Alice", "Bob"), setOf("Alice"))) // a leave, not a join
    }
}
