package dev.macromod.pathfinding

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private data class Node(val pos: Vec3i, val f: Double)
private data class Move(val target: Vec3i, val cost: Double)

/**
 * The built-in [Pathfinder]: A* over a voxel grid for a 2-block-tall agent (a player).
 *
 * A position is *standable* when the block below is solid and the two blocks at the
 * position (feet + head) are clear. Moves are: cardinal/diagonal walks, a one-block
 * step up (jump), falls of up to [PathParams.maxFall] blocks, and a one-block parkour
 * jump. The heuristic is octile distance scaled by the walk cost — admissible, so paths
 * are optimal for this move set.
 *
 * Stateless: the world ([BlockView]) and tunables ([PathParams]) are passed to
 * [findPath], so a single instance is safe to share and to keep as the default in
 * [Pathfinders]. Replace it by assigning a different [Pathfinder] to [Pathfinders.active].
 */
class AStarPathfinder : Pathfinder {

    override fun findPath(start: Vec3i, goal: Vec3i, view: BlockView, params: PathParams): List<Vec3i>? {
        if (!standable(start, view) || !standable(goal, view)) return null
        if (start == goal) return listOf(start)

        val open = PriorityQueue<Node>(compareBy { it.f })
        val g = HashMap<Vec3i, Double>()
        val cameFrom = HashMap<Vec3i, Vec3i>()
        val closed = HashSet<Vec3i>()

        g[start] = 0.0
        open.add(Node(start, heuristic(start, goal)))
        var expanded = 0

        while (open.isNotEmpty()) {
            val current = open.poll().pos
            if (current == goal) return reconstruct(cameFrom, goal)
            if (!closed.add(current)) continue
            if (++expanded > params.maxNodes) return null

            for (move in neighbors(current, view, params.maxFall)) {
                if (move.target in closed) continue
                val tentative = g.getValue(current) + move.cost
                if (tentative < (g[move.target] ?: Double.MAX_VALUE)) {
                    g[move.target] = tentative
                    cameFrom[move.target] = current
                    open.add(Node(move.target, tentative + heuristic(move.target, goal)))
                }
            }
        }
        return null
    }

    private fun reconstruct(cameFrom: Map<Vec3i, Vec3i>, goal: Vec3i): List<Vec3i> {
        val path = ArrayList<Vec3i>()
        var cur: Vec3i? = goal
        while (cur != null) {
            path.add(cur)
            cur = cameFrom[cur]
        }
        return path.asReversed()
    }

    /** Solid ground below, and feet + head clear. */
    private fun standable(p: Vec3i, view: BlockView) =
        view.isSolid(p.down()) && !view.isSolid(p) && !view.isSolid(p.up())

    /** Feet + head clear (no ground requirement) — used for corner-cutting and fall checks. */
    private fun clearColumn(p: Vec3i, view: BlockView) = !view.isSolid(p) && !view.isSolid(p.up())

    private fun neighbors(p: Vec3i, view: BlockView, maxFall: Int): List<Move> {
        val out = ArrayList<Move>(8)

        for (d in CARDINALS) {
            val level = p + d
            when {
                standable(level, view) -> out += Move(level, Cost.WALK)
                // step up one block (jump) — needs headroom above the start
                standable(level.up(), view) && !view.isSolid(p.up(2)) -> out += Move(level.up(), Cost.WALK + Cost.STEP_UP)
                // walk off an edge and fall to the first ground below
                clearColumn(level, view) -> {
                    var fell = 1
                    var probe = level.down()
                    while (fell <= maxFall) {
                        if (standable(probe, view)) { out += Move(probe, Cost.WALK + fell * Cost.FALL_PER); break }
                        if (view.isSolid(probe) || view.isSolid(probe.up())) break // blocked — can't fall through
                        probe = probe.down()
                        fell++
                    }
                }
            }

            // parkour: jump a one-block gap to land on the far side at the same level
            val land = level + d
            if (!standable(level, view) && clearColumn(level, view) && !view.isSolid(p.up(2)) && standable(land, view)) {
                out += Move(land, Cost.WALK * 2 + Cost.PARKOUR)
            }
        }

        for (d in DIAGONALS) {
            val level = p + d
            val cornerX = Vec3i(p.x + d.x, p.y, p.z)
            val cornerZ = Vec3i(p.x, p.y, p.z + d.z)
            // diagonal walk only on flat ground, and only if both corners are open (no clipping)
            if (standable(level, view) && clearColumn(cornerX, view) && clearColumn(cornerZ, view)) {
                out += Move(level, Cost.DIAGONAL)
            }
        }
        return out
    }

    /** Octile distance scaled by walk cost (+ a cheap vertical term) — admissible. */
    private fun heuristic(a: Vec3i, b: Vec3i): Double {
        val dx = abs(a.x - b.x)
        val dz = abs(a.z - b.z)
        val dy = abs(a.y - b.y)
        val dMax = max(dx, dz)
        val dMin = min(dx, dz)
        val horizontal = (dMax - dMin) * Cost.WALK + dMin * Cost.DIAGONAL
        return horizontal + dy * Cost.WALK * 0.5
    }

    companion object {
        private val CARDINALS = listOf(Vec3i(1, 0, 0), Vec3i(-1, 0, 0), Vec3i(0, 0, 1), Vec3i(0, 0, -1))
        private val DIAGONALS = listOf(Vec3i(1, 0, 1), Vec3i(1, 0, -1), Vec3i(-1, 0, 1), Vec3i(-1, 0, -1))
    }
}
