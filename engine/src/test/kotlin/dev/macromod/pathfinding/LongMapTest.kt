package dev.macromod.pathfinding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LongMapTest {
    @Test fun `LongDoubleMap stores, updates, and defaults on absent`() {
        val m = LongDoubleMap(4)
        assertEquals(-1.0, m.get(42L, -1.0), "absent key returns the default")
        m.put(42L, 3.5)
        assertEquals(3.5, m.get(42L, -1.0))
        m.put(42L, 9.0) // update in place
        assertEquals(9.0, m.get(42L, -1.0))
    }

    @Test fun `LongDoubleMap handles edge-bit keys (0, -1, MIN_VALUE) as distinct`() {
        val m = LongDoubleMap(4)
        m.put(0L, 1.0); m.put(-1L, 2.0); m.put(Long.MIN_VALUE, 3.0); m.put(Long.MAX_VALUE, 4.0)
        assertEquals(1.0, m.get(0L, -9.0))
        assertEquals(2.0, m.get(-1L, -9.0))
        assertEquals(3.0, m.get(Long.MIN_VALUE, -9.0))
        assertEquals(4.0, m.get(Long.MAX_VALUE, -9.0))
    }

    @Test fun `LongDoubleMap survives grow with thousands of packed keys`() {
        val m = LongDoubleMap(4)
        val n = 5000
        for (k in 0 until n) m.put(LongPos.pack(k, k % 256 - 64, -k), k.toDouble())
        for (k in 0 until n) assertEquals(k.toDouble(), m.get(LongPos.pack(k, k % 256 - 64, -k), -1.0), "key $k after grow")
        assertEquals(-1.0, m.get(LongPos.pack(n + 1, 0, 0), -1.0), "never-inserted key still defaults")
    }

    @Test fun `LongLongMap distinguishes present-with-zero-value from absent`() {
        val m = LongLongMap(4)
        assertFalse(m.containsKey(7L))
        m.put(7L, 0L) // value 0 is a real parent pointer (packs (0,0,0))
        assertTrue(m.containsKey(7L), "key present even though its value is 0")
        assertEquals(0L, m.get(7L))
        m.put(7L, 99L)
        assertEquals(99L, m.get(7L))
    }

    @Test fun `LongLongMap survives grow`() {
        val m = LongLongMap(4)
        val n = 3000
        for (k in 0 until n) m.put(k.toLong(), (k * 2).toLong())
        for (k in 0 until n) {
            assertTrue(m.containsKey(k.toLong()))
            assertEquals((k * 2).toLong(), m.get(k.toLong()))
        }
        assertFalse(m.containsKey(n.toLong() + 1))
    }

    @Test fun `LongSet add reports novelty and contains works across grow`() {
        val s = LongSet(4)
        assertTrue(s.add(5L), "first add is novel")
        assertFalse(s.add(5L), "second add is not novel")
        assertTrue(s.contains(5L))
        assertFalse(s.contains(6L))
        val n = 4000
        for (k in 0 until n) s.add(LongPos.pack(k, 0, k))
        for (k in 0 until n) assertTrue(s.contains(LongPos.pack(k, 0, k)), "member $k after grow")
        assertFalse(s.contains(LongPos.pack(n + 1, 0, n + 1)))
    }
}
