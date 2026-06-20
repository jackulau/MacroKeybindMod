package dev.macromod.pathfinding

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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

        // Working sets key on LongPos-packed coords in primitive structures: the open list is a
        // binary min-heap over long[]/double[], and g/cameFrom/closed are primitive long maps/set.
        // So the inner loop allocates no Vec3i, boxes no Long/Double, and allocates no heap node per
        // expansion. Vec3i stays only at the API boundary and for move generation.
        val goalKey = LongPos.pack(goal)
        val open = LongMinHeap()
        val g = LongDoubleMap()
        val cameFrom = LongLongMap()
        val closed = LongSet()

        g.put(LongPos.pack(start), 0.0)
        open.push(LongPos.pack(start), heuristic(start, goal))
        var expanded = 0

        while (!open.isEmpty) {
            val currentKey = open.poll()
            if (currentKey == goalKey) return reconstruct(cameFrom, goalKey)
            if (!closed.add(currentKey)) continue
            if (++expanded > params.maxNodes) return null

            val current = LongPos.unpack(currentKey)
            val gCurrent = g.get(currentKey, Double.MAX_VALUE)
            for (move in neighbors(current, view, params.maxFall)) {
                val targetKey = LongPos.pack(move.target)
                if (closed.contains(targetKey)) continue
                val tentative = gCurrent + move.cost
                if (tentative < g.get(targetKey, Double.MAX_VALUE)) {
                    g.put(targetKey, tentative)
                    cameFrom.put(targetKey, currentKey)
                    open.push(targetKey, tentative + heuristic(move.target, goal))
                }
            }
        }
        return null
    }

    private fun reconstruct(cameFrom: LongLongMap, goalKey: Long): List<Vec3i> {
        val path = ArrayList<Vec3i>()
        var cur = goalKey
        path.add(LongPos.unpack(cur))
        while (cameFrom.containsKey(cur)) {
            cur = cameFrom.get(cur)
            path.add(LongPos.unpack(cur))
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
