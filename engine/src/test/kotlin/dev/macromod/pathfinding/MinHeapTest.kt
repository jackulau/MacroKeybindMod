package dev.macromod.pathfinding

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MinHeapTest {
    @Test fun `polls payloads in ascending priority order`() {
        val h = LongMinHeap(4)
        assertTrue(h.isEmpty)
        // push out of order; key encodes the priority so we can check the payload travels with it
        for (p in intArrayOf(5, 1, 3, 2, 4, 0)) h.push(p.toLong() + 100, p.toDouble())
        assertFalse(h.isEmpty)
        val polled = ArrayList<Long>()
        while (!h.isEmpty) polled.add(h.poll())
        assertEquals(listOf(100L, 101L, 102L, 103L, 104L, 105L), polled, "payloads come out smallest-priority first")
    }

    @Test fun `grows past initial capacity and keeps full order`() {
        val h = LongMinHeap(2) // force several grows
        val n = 2000
        val order = (0 until n).shuffled(Random(42))
        for (i in order) h.push(i.toLong(), i.toDouble())
        val out = ArrayList<Long>(n)
        while (!h.isEmpty) out.add(h.poll())
        assertEquals((0 until n).map { it.toLong() }, out, "all $n entries poll in ascending order after grows")
    }

    @Test fun `tied priorities all surface with no loss`() {
        val h = LongMinHeap(4)
        val n = 200
        for (k in 0 until n) h.push(k.toLong(), 7.0) // every entry the same priority
        val seen = HashSet<Long>()
        repeat(n) { seen.add(h.poll()) }
        assertTrue(h.isEmpty)
        assertEquals((0 until n).map { it.toLong() }.toSet(), seen, "no tied entry dropped or duplicated")
    }

    @Test fun `interleaved push and poll always yields the current minimum`() {
        val h = LongMinHeap(4)
        val rnd = Random(7)
        val mirror = ArrayList<Double>() // sorted reference of live priorities
        repeat(3000) {
            if (h.isEmpty || rnd.nextBoolean()) {
                val p = rnd.nextInt(0, 500).toDouble()
                h.push((p * 1000).toLong(), p)
                mirror.add(p)
                mirror.sort()
            } else {
                val expected = mirror.removeAt(0) // current minimum priority
                val gotKey = h.poll()
                assertEquals((expected * 1000).toLong(), gotKey, "poll must return the live minimum's payload")
            }
        }
    }
}
