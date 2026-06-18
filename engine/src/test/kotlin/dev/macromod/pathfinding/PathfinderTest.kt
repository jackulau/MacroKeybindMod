package dev.macromod.pathfinding

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A synthetic world: solid below [floorY], plus any explicitly solid blocks. */
private class TestWorld(private val solids: Set<Vec3i> = emptySet(), private val floorY: Int = 0) : BlockView {
    override fun isSolid(pos: Vec3i): Boolean = pos.y <= floorY || pos in solids
}

class PathfinderTest {

    private val astar = AStarPathfinder()

    private fun assertContiguous(path: List<Vec3i>, start: Vec3i, goal: Vec3i) {
        assertEquals(start, path.first())
        assertEquals(goal, path.last())
        for (i in 1 until path.size) {
            val a = path[i - 1]
            val b = path[i]
            val horiz = max(abs(a.x - b.x), abs(a.z - b.z))
            assertTrue(horiz <= 1, "step $a -> $b is not to an adjacent column")
        }
    }

    @Test fun `straight line on flat ground`() {
        val path = astar.findPath(Vec3i(0, 1, 0), Vec3i(5, 1, 0), TestWorld())
        assertNotNull(path)
        assertContiguous(path, Vec3i(0, 1, 0), Vec3i(5, 1, 0))
        assertEquals(6, path.size) // 0..5 inclusive
    }

    @Test fun `diagonal is taken on open ground`() {
        val path = astar.findPath(Vec3i(0, 1, 0), Vec3i(5, 1, 5), TestWorld())
        assertNotNull(path)
        assertContiguous(path, Vec3i(0, 1, 0), Vec3i(5, 1, 5))
        assertEquals(6, path.size) // 5 diagonal steps
    }

    @Test fun `detours around a wall`() {
        // a wall at x=2 blocking z in -1..1, two blocks tall
        val wall = buildSet {
            for (z in -1..1) { add(Vec3i(2, 1, z)); add(Vec3i(2, 2, z)) }
        }
        val path = astar.findPath(Vec3i(0, 1, 0), Vec3i(4, 1, 0), TestWorld(wall))
        assertNotNull(path)
        assertContiguous(path, Vec3i(0, 1, 0), Vec3i(4, 1, 0))
        assertTrue(path.none { it.x == 2 && it.z in -1..1 }, "path should go around the wall")
    }

    @Test fun `steps up onto a block`() {
        val step = setOf(Vec3i(3, 1, 0)) // a one-block step at x=3
        val path = astar.findPath(Vec3i(0, 1, 0), Vec3i(3, 2, 0), TestWorld(step))
        assertNotNull(path)
        assertEquals(Vec3i(3, 2, 0), path.last())
        assertTrue(path.contains(Vec3i(2, 1, 0)), "should approach the step before climbing")
    }

    @Test fun `falls down to a lower platform`() {
        // a pillar at x=0 so the agent starts elevated; flat floor elsewhere at y=0
        val pillar = setOf(Vec3i(0, 1, 0), Vec3i(0, 2, 0))
        val path = astar.findPath(Vec3i(0, 3, 0), Vec3i(2, 1, 0), TestWorld(pillar))
        assertNotNull(path)
        assertEquals(Vec3i(0, 3, 0), path.first())
        assertEquals(Vec3i(2, 1, 0), path.last())
    }

    @Test fun `no path across an uncrossable gap`() {
        // two platforms at y=0 (x in 0..1 and x in 5..6); deep void between (floor far below)
        val platforms = buildSet {
            for (x in 0..1) add(Vec3i(x, 0, 0))
            for (x in 5..6) add(Vec3i(x, 0, 0))
        }
        val world = TestWorld(platforms, floorY = -10)
        val path = astar.findPath(Vec3i(0, 1, 0), Vec3i(5, 1, 0), world, PathParams(maxFall = 3))
        assertNull(path)
    }

    @Test fun `parkours across a one-block gap`() {
        // platforms at x = 0,1,3,4 ; a single missing block at x=2 over a void
        val platforms = buildSet { for (x in intArrayOf(0, 1, 3, 4)) add(Vec3i(x, 0, 0)) }
        val path = astar.findPath(Vec3i(0, 1, 0), Vec3i(4, 1, 0), TestWorld(platforms, floorY = -10), PathParams(maxFall = 3))
        assertNotNull(path)
        assertEquals(Vec3i(4, 1, 0), path.last())
        assertTrue(path.contains(Vec3i(1, 1, 0)) && path.contains(Vec3i(3, 1, 0)), "should jump from x=1 to x=3")
    }

    @Test fun `start equals goal`() {
        assertEquals(listOf(Vec3i(0, 1, 0)), astar.findPath(Vec3i(0, 1, 0), Vec3i(0, 1, 0), TestWorld()))
    }

    // --- SPI / registry --------------------------------------------------------------------

    @Test fun `the default active pathfinder is the built-in A-star`() {
        Pathfinders.reset()
        assertTrue(Pathfinders.active is AStarPathfinder)
    }

    @Test fun `a custom pathfinder can be plugged in via the registry`() {
        val marker = listOf(Vec3i(7, 7, 7))
        try {
            // a trivial custom pathfinder (lambda) that ignores the world and returns a fixed path
            Pathfinders.active = Pathfinder { _, _, _, _ -> marker }
            assertEquals(marker, Pathfinders.active.findPath(Vec3i(0, 1, 0), Vec3i(5, 1, 0), TestWorld()))
        } finally {
            Pathfinders.reset()
        }
        // after reset, the built-in A* is back and actually searches
        val path = Pathfinders.active.findPath(Vec3i(0, 1, 0), Vec3i(3, 1, 0), TestWorld())
        assertNotNull(path)
        assertEquals(Vec3i(3, 1, 0), path.last())
    }

    @Test fun `PathParams maxNodes caps the search`() {
        // a near-zero node budget forces the search to give up (null) before reaching a far goal
        val none = astar.findPath(Vec3i(0, 1, 0), Vec3i(9, 1, 0), TestWorld(), PathParams(maxNodes = 1))
        assertNull(none)
    }
}
