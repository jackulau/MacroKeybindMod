package dev.macromod.pathfinding

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The built-in [Pathfinder]: A* over a voxel grid for a 2-block-tall agent (a player).
 *
 * A position is *standable* when the block below is solid and the two blocks at the
 * position (feet + head) are clear. Moves are: cardinal/diagonal walks, a one-block
 * step up (jump), falls of up to [PathParams.maxFall] blocks, and a one-block parkour
 * jump. The heuristic is horizontal octile distance scaled by the walk cost: admissible
 * AND consistent (every move advances horizontal by >= one cardinal/diagonal unit at a
 * cost >= the matching octile weight), so with the never-reopen closed set the first pop
 * of each node is its optimal g and paths are cost-optimal for this move set. It carries
 * no vertical term on purpose: a fall covers several blocks of descent for ~one walk plus
 * one horizontal step, so any positive per-block vertical estimate overestimates the true
 * cost-to-go and would make the heuristic inadmissible (returning costlier-than-optimal
 * paths on terrain).
 *
 * Stateless: the world ([BlockView]) and tunables ([PathParams]) are passed to
 * [findPath], so a single instance is safe to share and to keep as the default in
 * [Pathfinders]. Replace it by assigning a different [Pathfinder] to [Pathfinders.active].
 */
class AStarPathfinder internal constructor(
    /**
     * Whether the search is informed by [heuristic]. The shared default is `true` (A*). Tests
     * construct an `false` instance via [dijkstra] to get a provably-optimal Dijkstra ground truth
     * (h = 0 is trivially consistent), against which the informed search's path cost is asserted equal.
     */
    private val informed: Boolean,
) : Pathfinder {

    /** The shared default: an informed A* search with the admissible, consistent octile heuristic. */
    constructor() : this(informed = true)

    override fun findPath(start: Vec3i, goal: Vec3i, view: BlockView, params: PathParams): List<Vec3i>? {
        if (!standable(start.x, start.y, start.z, view) || !standable(goal.x, goal.y, goal.z, view)) return null
        if (start == goal) return listOf(start)

        // The whole search runs on LongPos-packed primitive coords: the open list is a binary
        // min-heap over long[]/double[], g/cameFrom/closed are primitive long maps/set, and neighbor
        // generation emits straight into the reuse buffer below. So an expansion allocates no Vec3i,
        // no Move, no list, no heap node, and boxes no Long/Double. Vec3i lives only at this boundary.
        val goalKey = LongPos.pack(goal)
        val open = LongMinHeap()
        val g = LongDoubleMap()
        val cameFrom = LongLongMap()
        val closed = LongSet()
        // Per-search neighbor buffer (a player has at most 12 moves: 4 walk/step/fall + 4 parkour +
        // 4 diagonal). Allocated ONCE per findPath — never a field, since the instance is shared and
        // documented thread-safe — and refilled each expansion.
        val nbrKeys = LongArray(16)
        val nbrCosts = DoubleArray(16)

        g.put(LongPos.pack(start), 0.0)
        open.push(LongPos.pack(start), heuristic(start.x, start.y, start.z, goal))
        var expanded = 0

        while (!open.isEmpty) {
            val currentKey = open.poll()
            if (currentKey == goalKey) return reconstruct(cameFrom, goalKey)
            if (!closed.add(currentKey)) continue
            if (++expanded > params.maxNodes) return null

            val gCurrent = g.get(currentKey, Double.MAX_VALUE)
            val n = neighborsInto(
                LongPos.x(currentKey), LongPos.y(currentKey), LongPos.z(currentKey),
                view, params.maxFall, nbrKeys, nbrCosts,
            )
            var i = 0
            while (i < n) {
                val targetKey = nbrKeys[i]
                val cost = nbrCosts[i]
                i++
                if (closed.contains(targetKey)) continue
                val tentative = gCurrent + cost
                if (tentative < g.get(targetKey, Double.MAX_VALUE)) {
                    g.put(targetKey, tentative)
                    cameFrom.put(targetKey, currentKey)
                    open.push(
                        targetKey,
                        tentative + heuristic(LongPos.x(targetKey), LongPos.y(targetKey), LongPos.z(targetKey), goal),
                    )
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
    private fun standable(x: Int, y: Int, z: Int, view: BlockView) =
        view.isSolid(x, y - 1, z) && !view.isSolid(x, y, z) && !view.isSolid(x, y + 1, z)

    /** Feet + head clear (no ground requirement) — used for corner-cutting and fall checks. */
    private fun clearColumn(x: Int, y: Int, z: Int, view: BlockView) =
        !view.isSolid(x, y, z) && !view.isSolid(x, y + 1, z)

    /**
     * Generates the moves out of (px,py,pz) into the caller's reuse buffers and returns the count.
     * Pure primitive coord math + [BlockView.isSolid] queries — no Vec3i, no Move, no list — so an
     * expansion allocates nothing. Emits in the same order as the former Vec3i `neighbors()` (each
     * cardinal with its parkour, then the diagonals) so the search stays output-identical.
     */
    private fun neighborsInto(
        px: Int, py: Int, pz: Int, view: BlockView, maxFall: Int,
        outKeys: LongArray, outCosts: DoubleArray,
    ): Int {
        var n = 0
        var ci = 0
        while (ci < 4) {
            val dx = CARD_DX[ci]
            val dz = CARD_DZ[ci]
            val lx = px + dx
            val lz = pz + dz
            when {
                standable(lx, py, lz, view) -> {
                    outKeys[n] = LongPos.pack(lx, py, lz); outCosts[n] = Cost.WALK; n++
                }
                // step up one block (jump) — needs headroom above the start
                standable(lx, py + 1, lz, view) && !view.isSolid(px, py + 2, pz) -> {
                    outKeys[n] = LongPos.pack(lx, py + 1, lz); outCosts[n] = Cost.WALK + Cost.STEP_UP; n++
                }
                // walk off an edge and fall to the first ground below
                clearColumn(lx, py, lz, view) -> {
                    var fell = 1
                    var probeY = py - 1
                    while (fell <= maxFall) {
                        if (standable(lx, probeY, lz, view)) {
                            outKeys[n] = LongPos.pack(lx, probeY, lz)
                            outCosts[n] = Cost.WALK + fell * Cost.FALL_PER
                            n++
                            break
                        }
                        if (view.isSolid(lx, probeY, lz) || view.isSolid(lx, probeY + 1, lz)) break // blocked
                        probeY--
                        fell++
                    }
                }
            }

            // parkour: jump a one-block gap to land on the far side at the same level
            if (!standable(lx, py, lz, view) && clearColumn(lx, py, lz, view) &&
                !view.isSolid(px, py + 2, pz) && standable(lx + dx, py, lz + dz, view)
            ) {
                outKeys[n] = LongPos.pack(lx + dx, py, lz + dz); outCosts[n] = Cost.WALK * 2 + Cost.PARKOUR; n++
            }
            ci++
        }

        var di = 0
        while (di < 4) {
            val dx = DIAG_DX[di]
            val dz = DIAG_DZ[di]
            val lx = px + dx
            val lz = pz + dz
            // diagonal walk only on flat ground, and only if both corners are open (no clipping)
            if (standable(lx, py, lz, view) && clearColumn(px + dx, py, pz, view) && clearColumn(px, py, pz + dz, view)) {
                outKeys[n] = LongPos.pack(lx, py, lz); outCosts[n] = Cost.DIAGONAL; n++
            }
            di++
        }
        return n
    }

    /**
     * Horizontal octile distance scaled by walk cost: admissible AND consistent (see the class
     * doc). No vertical term: a fall amortizes to ~one walk-cost per several blocks of descent, so
     * crediting any positive per-block vertical cost would overestimate cost-to-go and break
     * admissibility. Returns 0 for the [dijkstra] ground-truth variant ([informed] = false).
     */
    private fun heuristic(ax: Int, ay: Int, az: Int, b: Vec3i): Double {
        if (!informed) return 0.0
        val dx = abs(ax - b.x)
        val dz = abs(az - b.z)
        val dMax = max(dx, dz)
        val dMin = min(dx, dz)
        return (dMax - dMin) * Cost.WALK + dMin * Cost.DIAGONAL
    }

    companion object {
        /**
         * A Dijkstra (uninformed, h = 0) ground-truth search over the identical move model, for
         * tests only: h = 0 is trivially consistent, so its path is cost-optimal and the informed
         * search must match it. Not for production use (it expands far more nodes).
         */
        internal fun dijkstra(): AStarPathfinder = AStarPathfinder(informed = false)

        // Cardinal then diagonal offsets, in the SAME order the old Vec3i lists used, so the neighbor
        // emission order (and thus the heap's tie-break) is unchanged.
        private val CARD_DX = intArrayOf(1, -1, 0, 0)
        private val CARD_DZ = intArrayOf(0, 0, 1, -1)
        private val DIAG_DX = intArrayOf(1, 1, -1, -1)
        private val DIAG_DZ = intArrayOf(1, -1, 1, -1)
    }
}
