package dev.macromod.pathfinding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockViewTest {
    @Test fun `the primitive overload defaults to the Vec3i implementation`() {
        // A view implementing only the Vec3i SAM (a lambda) must still answer the primitive form
        // via the default, building a Vec3i to delegate.
        val seen = ArrayList<Vec3i>()
        val view = BlockView { p -> seen.add(p); p.y <= 0 }
        assertTrue(view.isSolid(5, 0, -3), "delegates to the Vec3i form (y<=0 is solid)")
        assertFalse(view.isSolid(5, 1, -3))
        assertEquals(listOf(Vec3i(5, 0, -3), Vec3i(5, 1, -3)), seen, "default packed each call into a Vec3i and delegated")
    }

    @Test fun `a primitive-overriding view is honored and the Vec3i form routes to it`() {
        val view = object : BlockView {
            override fun isSolid(pos: Vec3i): Boolean = isSolid(pos.x, pos.y, pos.z)
            override fun isSolid(x: Int, y: Int, z: Int): Boolean = (x + y + z) % 2 == 0
        }
        assertTrue(view.isSolid(1, 1, 0)) // sum 2, even
        assertFalse(view.isSolid(1, 1, 1)) // sum 3, odd
        assertTrue(view.isSolid(Vec3i(2, 0, 0)), "Vec3i form delegates to the primitive override")
        assertFalse(view.isSolid(Vec3i(2, 1, 0)))
    }

    @Test fun `A-star drives a primitive-overriding view to a correct path`() {
        // End-to-end: the built-in A* must produce the same path through a view whose only real
        // implementation is the primitive overload (open flat ground at y<=0).
        val primitiveGround = object : BlockView {
            override fun isSolid(pos: Vec3i): Boolean = isSolid(pos.x, pos.y, pos.z)
            override fun isSolid(x: Int, y: Int, z: Int): Boolean = y <= 0
        }
        val path = AStarPathfinder().findPath(Vec3i(0, 1, 0), Vec3i(4, 1, 0), primitiveGround)
        assertEquals(Vec3i(0, 1, 0), path?.first())
        assertEquals(Vec3i(4, 1, 0), path?.last())
        assertEquals(5, path?.size) // 0..4 inclusive on a straight line
    }
}
