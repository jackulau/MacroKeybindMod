package dev.macromod.pathfinding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathExecutorTest {
    @Test fun `faces and moves toward the next waypoint`() {
        val executor = PathExecutor(listOf(Vec3i(0, 1, 0), Vec3i(0, 1, 1), Vec3i(0, 1, 2)))
        val cmd = executor.nextStep(Vec3i(0, 1, 0))!!
        assertEquals(0f, cmd.yaw) // +Z = south
        assertTrue("forward" in cmd.keys)
    }

    @Test fun `advances through waypoints and finishes`() {
        val executor = PathExecutor(listOf(Vec3i(0, 1, 0), Vec3i(1, 1, 0)))
        executor.nextStep(Vec3i(0, 1, 0))
        assertNull(executor.nextStep(Vec3i(1, 1, 0)))
        assertTrue(executor.isDone)
    }

    @Test fun `jumps when the next waypoint is higher`() {
        val cmd = PathExecutor(listOf(Vec3i(0, 1, 0), Vec3i(0, 2, 1))).nextStep(Vec3i(0, 1, 0))!!
        assertTrue("jump" in cmd.keys)
    }

    @Test fun `yaw faces west for -X and east for +X`() {
        assertEquals(90f, PathExecutor(listOf(Vec3i(0, 1, 0), Vec3i(-1, 1, 0))).nextStep(Vec3i(0, 1, 0))!!.yaw)
        assertEquals(-90f, PathExecutor(listOf(Vec3i(0, 1, 0), Vec3i(1, 1, 0))).nextStep(Vec3i(0, 1, 0))!!.yaw)
    }

    @Test fun `end to end pathfind then execute reaches the goal`() {
        // flat world; find a path, then walk it by feeding back the planned positions
        val world = BlockView { it.y <= 0 }
        val path = AStarPathfinder().findPath(Vec3i(0, 1, 0), Vec3i(3, 1, 0), world)!!
        val executor = PathExecutor(path)
        var pos = path.first()
        var guard = 0
        while (!executor.isDone && guard++ < 50) {
            val cmd = executor.nextStep(pos) ?: break
            // simulate moving one block toward the faced direction by stepping to the next waypoint
            pos = path[executor.index]
        }
        assertTrue(executor.isDone)
    }
}
