package dev.macromod.pathfinding

import kotlin.test.Test
import kotlin.test.assertEquals

class LongPosTest {
    private fun roundTrip(x: Int, y: Int, z: Int) {
        val p = LongPos.pack(x, y, z)
        assertEquals(x, LongPos.x(p), "x of ($x,$y,$z)")
        assertEquals(y, LongPos.y(p), "y of ($x,$y,$z)")
        assertEquals(z, LongPos.z(p), "z of ($x,$y,$z)")
        assertEquals(Vec3i(x, y, z), LongPos.unpack(p))
    }

    @Test fun `round-trips the origin and small positive coords`() {
        roundTrip(0, 0, 0); roundTrip(1, 2, 3); roundTrip(100, 64, 200)
    }

    @Test fun `round-trips negative coords including below-zero y`() {
        roundTrip(-1, -1, -1); roundTrip(-100, -64, -200); roundTrip(-2048, -64, 1500)
    }

    @Test fun `round-trips the full MC world-edge range`() {
        // x,z are signed 26-bit (-2^25..2^25-1); y is signed 12-bit (-2048..2047).
        roundTrip(33_554_431, 2047, 33_554_431)
        roundTrip(-33_554_432, -2048, -33_554_432)
        roundTrip(30_000_000, 320, -30_000_000) // real world border + build-height ceiling
    }

    @Test fun `distinct positions pack to distinct longs`() {
        val a = LongPos.pack(10, 20, 30)
        val b = LongPos.pack(30, 20, 10) // x/z swapped
        val c = LongPos.pack(10, 21, 30) // y+1
        assertEquals(3, setOf(a, b, c).size)
    }
}
