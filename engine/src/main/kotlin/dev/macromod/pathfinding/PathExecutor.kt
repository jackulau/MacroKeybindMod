package dev.macromod.pathfinding

import kotlin.math.abs
import kotlin.math.atan2

/** A per-tick movement decision: face [yaw] (degrees) and hold [keys]. */
data class MovementCommand(val yaw: Float, val keys: Set<String>)

/**
 * Drives a player along a precomputed [Pathfinder] path. Each tick, given the player's
 * current block position, it returns which way to look and which keys to hold to advance
 * toward the next waypoint — pure decision logic. The Fabric layer reads the real position
 * and applies the command through the [dev.macromod.engine.action.InputController], so this
 * is unit-testable without Minecraft.
 */
class PathExecutor(private val path: List<Vec3i>, private val reachRadius: Int = 0) {
    var index = 0
        private set

    val isDone: Boolean get() = index >= path.size

    /** Returns the movement for this tick, or null when the path is finished. */
    fun nextStep(current: Vec3i): MovementCommand? {
        while (index < path.size && reached(current, path[index])) index++
        if (index >= path.size) return null

        val target = path[index]
        val keys = linkedSetOf("forward")
        if (target.y > current.y) keys.add("jump") // step up / climb
        return MovementCommand(yawTo(current, target), keys)
    }

    private fun reached(current: Vec3i, waypoint: Vec3i): Boolean =
        abs(current.x - waypoint.x) <= reachRadius &&
            abs(current.z - waypoint.z) <= reachRadius &&
            abs(current.y - waypoint.y) <= 1

    /** Minecraft yaw: 0 = +Z (south), 90 = -X (west), so yaw = atan2(-dx, dz). */
    private fun yawTo(from: Vec3i, to: Vec3i): Float {
        val dx = (to.x - from.x).toDouble()
        val dz = (to.z - from.z).toDouble()
        return Math.toDegrees(atan2(-dx, dz)).toFloat()
    }
}
