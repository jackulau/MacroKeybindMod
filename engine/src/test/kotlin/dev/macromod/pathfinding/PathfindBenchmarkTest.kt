package dev.macromod.pathfinding

import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Flat open ground at y=0 with an optional wall, for scale benchmarking. */
private class OpenWorld(private val solids: Set<Vec3i> = emptySet()) : BlockView {
    override fun isSolid(pos: Vec3i): Boolean = pos.y <= 0 || pos in solids
}

/**
 * Scale + throughput check for the long-packed A*. The small unit tests in [PathfinderTest] prove the
 * search is output-equivalent to the old Vec3i-keyed version; this proves it stays correct on a large
 * search that expands many nodes and prints the optimized throughput (no Vec3i allocation or
 * Long/Double boxing per node). The bound is deliberately loose so it never flakes on a slow runner.
 */
class PathfindBenchmarkTest {
    private val astar = AStarPathfinder()

    @Test fun `a large detour search stays correct and runs fast`() {
        // A wall at x=40 spanning z in -60..40 (two tall) with the only gap above z=40, so the path
        // must fan north around it and back: a search that expands a few thousand nodes.
        val wall = buildSet {
            for (z in -60..40) { add(Vec3i(40, 1, z)); add(Vec3i(40, 2, z)) }
        }
        val world = OpenWorld(wall)
        val start = Vec3i(0, 1, 0)
        val goal = Vec3i(80, 1, 0)
        val params = PathParams(maxNodes = 200_000)

        val path = astar.findPath(start, goal, world, params)
        assertNotNull(path, "the detour around the wall must be found")
        assertEquals(start, path!!.first())
        assertEquals(goal, path.last())
        // contiguous (no teleporting) and actually clears the wall column
        for (i in 1 until path.size) {
            assertTrue(max(abs(path[i - 1].x - path[i].x), abs(path[i - 1].z - path[i].z)) <= 1, "non-adjacent step")
        }
        assertTrue(path.any { it.z > 40 }, "the path must detour past the wall gap")

        repeat(20) { astar.findPath(start, goal, world, params) } // warm up the JIT
        val iters = 200
        val t0 = System.nanoTime()
        repeat(iters) { astar.findPath(start, goal, world, params) }
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        println("[bench] long-packed A*: %d detour searches (%d-node path) in %.0f ms = %.3f ms/search"
            .format(iters, path.size, ms, ms / iters))
        assertTrue(ms < 20_000, "200 detour searches should finish well under 20s; took ${ms}ms")
    }

    @Suppress("DEPRECATION") // Thread.id (vs JDK 19+ threadId()) for compile-target portability
    @Test fun `neighbor expansion adds no per-node allocation (regression guard)`() {
        // Proves the expansion loop is allocation-free. The old neighbors() returned a List<Move>:
        // the Move objects, their ArrayList, and the Vec3i targets they held all ESCAPE into the
        // returned list, so the JIT cannot scalarize them — that was ~3 MB of real garbage on this
        // search. The rewrite emits into a reused buffer, so the only per-search allocation left is
        // the primitive working sets (g/cameFrom/closed/heap backing arrays) plus the result path,
        // which scale with the search and are inherent. Measuring that total below a ceiling the old
        // per-node churn would blow past is a durable regression guard.
        // (Note: the short-lived Vec3i in BlockView's default isSolid(x,y,z) delegation does NOT
        // escape and IS JIT-scalarized, so it costs nothing here — the primitive overload's value is
        // the primitive neighbor path and JIT-independent allocation-freedom, not a HotSpot byte delta.)
        val raw = ManagementFactory.getThreadMXBean()
        if (raw !is com.sun.management.ThreadMXBean || !raw.isThreadAllocatedMemorySupported) return // non-HotSpot: skip
        raw.isThreadAllocatedMemoryEnabled = true
        val tid = Thread.currentThread().id

        fun solid(x: Int, y: Int, z: Int) = y <= 0 || (x == 40 && (y == 1 || y == 2) && z in -60..40)
        val view = object : BlockView {
            override fun isSolid(pos: Vec3i) = isSolid(pos.x, pos.y, pos.z)
            override fun isSolid(x: Int, y: Int, z: Int) = solid(x, y, z)
        }
        val start = Vec3i(0, 1, 0)
        val goal = Vec3i(80, 1, 0)
        val params = PathParams(maxNodes = 200_000)
        val path = astar.findPath(start, goal, view, params)
        assertNotNull(path)

        repeat(20) { astar.findPath(start, goal, view, params) } // warm the JIT
        val iters = 50
        val before = raw.getThreadAllocatedBytes(tid)
        repeat(iters) { astar.findPath(start, goal, view, params) }
        val perSearch = (raw.getThreadAllocatedBytes(tid) - before) / iters
        println("[alloc] %,d B per detour search (%d-node path) — working sets + path only".format(perSearch, path!!.size))
        // The inherent working-set + path allocation for this search is well under 1 MB; re-adding
        // per-node Move/ArrayList/Vec3i churn (~3 MB here) would blow past this generous ceiling.
        assertTrue(perSearch in 1..1_500_000, "per-search allocation $perSearch B is outside the no-per-node-churn band")
    }
}
