package dev.macromod.pathfinding

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Proves the built-in A* returns COST-OPTIMAL paths on terrain, not just on flat ground.
 *
 * The optimality claim hinges on the heuristic being admissible (and, with the never-reopen closed
 * set, consistent). The previous heuristic carried a `dy * WALK * 0.5` vertical term that
 * overestimated cost-to-go whenever the goal was reachable by falling (a fall covers several blocks
 * of descent for ~one walk cost), so A* committed early to and never reopened wrong nodes and
 * returned costlier-than-optimal paths on terraced worlds. This compares the informed search against
 * an uninformed Dijkstra (h = 0, trivially optimal) over the identical move model: equal costs prove
 * admissibility holds across terrain.
 */
class AStarOptimalityTest {

    /** A bounded voxel world: column (x,z) is solid from y=0 up to height[x][z]; air above; the
     *  walkable surface is at y = height+1. Out-of-bounds is air (void). */
    private class HeightWorld(val size: Int, val height: Array<IntArray>) : BlockView {
        override fun isSolid(x: Int, y: Int, z: Int): Boolean =
            x in 0 until size && z in 0 until size && y in 0..height[x][z]
        override fun isSolid(pos: Vec3i): Boolean = isSolid(pos.x, pos.y, pos.z)
        fun surface(x: Int, z: Int): Vec3i = Vec3i(x, height[x][z] + 1, z)
    }

    /** Cost of one path step, classified by its delta. Mirrors [Cost] and the move model exactly;
     *  throws on an impossible step so a malformed path can never silently score as cheap. */
    private fun stepCost(from: Vec3i, to: Vec3i): Double {
        val dx = to.x - from.x; val dy = to.y - from.y; val dz = to.z - from.z
        val adx = abs(dx); val adz = abs(dz)
        return when {
            dy == 0 && adx + adz == 1 -> Cost.WALK                              // cardinal walk
            dy == 0 && adx == 1 && adz == 1 -> Cost.DIAGONAL                    // diagonal walk
            dy == 1 && adx + adz == 1 -> Cost.WALK + Cost.STEP_UP               // step up
            dy < 0 && adx + adz == 1 -> Cost.WALK + (-dy) * Cost.FALL_PER       // fall (1 cardinal + |dy| down)
            dy == 0 && (adx == 2 && adz == 0 || adx == 0 && adz == 2) -> Cost.WALK * 2 + Cost.PARKOUR // parkour
            else -> error("unexpected step $from -> $to (d=$dx,$dy,$dz)")
        }
    }

    private fun pathCost(path: List<Vec3i>): Double {
        var c = 0.0
        for (i in 1 until path.size) c += stepCost(path[i - 1], path[i])
        return c
    }

    @Test fun `A-star path cost equals Dijkstra optimum on random terraced worlds`() {
        val astar = AStarPathfinder()
        val dijkstra = AStarPathfinder.dijkstra()
        val params = PathParams(maxFall = 3)
        val size = 6
        var compared = 0
        var withVertical = 0
        for (seed in 0 until 4000) {
            val rng = Random(seed)
            val height = Array(size) { IntArray(size) { 2 + rng.nextInt(4) } } // base 2, relief 0..3
            val world = HeightWorld(size, height)
            val start = world.surface(rng.nextInt(size), rng.nextInt(size))
            val goal = world.surface(rng.nextInt(size), rng.nextInt(size))
            if (start == goal) continue

            val a = astar.findPath(start, goal, world, params)
            val d = dijkstra.findPath(start, goal, world, params)
            // Reachability must agree (same move model); only compare when a route exists.
            assertEquals(d == null, a == null, "reachability disagreement at seed $seed: $start -> $goal")
            if (a == null || d == null) continue

            compared++
            val ca = pathCost(a)
            val cd = pathCost(d)
            assertTrue(
                ca <= cd + 1e-9,
                "A* returned a SUBOPTIMAL path at seed $seed: $start -> $goal | A*=$ca optimal=$cd",
            )
            // With an admissible+consistent heuristic the informed search is itself optimal -> exactly equal.
            assertEquals(cd, ca, 1e-9, "A* cost != optimal at seed $seed: $start -> $goal")
            if (a.any { it.y != start.y }) withVertical++
        }
        assertTrue(compared > 500, "too few reachable cases compared ($compared) - test world is degenerate")
        assertTrue(withVertical > 100, "test never exercised vertical movement ($withVertical) - not proving the terrain case")
    }

    @Test fun `the documented inadmissible repro is now optimal`() {
        // A hand-built terrace where the old vertical term made A* overshoot: a high start, a goal
        // two blocks lower and offset, reachable by a single fall. Optimal must walk-off-and-fall.
        val size = 6
        val height = Array(size) { x -> IntArray(size) { z -> if (x >= 3) 1 else 3 } } // a 2-block step at x=3
        val world = HeightWorld(size, height)
        val astar = AStarPathfinder()
        val dijkstra = AStarPathfinder.dijkstra()
        val params = PathParams(maxFall = 3)
        val start = world.surface(0, 3) // on the high shelf
        val goal = world.surface(5, 2)  // on the low shelf, reached by walking off the 2-block step and falling
        val a = astar.findPath(start, goal, world, params)
        val d = dijkstra.findPath(start, goal, world, params)
        assertNotNull(a); assertNotNull(d)
        assertEquals(pathCost(d), pathCost(a), 1e-9, "A* not optimal on the terrace repro")
    }
}
