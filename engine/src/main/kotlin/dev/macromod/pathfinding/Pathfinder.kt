package dev.macromod.pathfinding

/** An integer block position. */
data class Vec3i(val x: Int, val y: Int, val z: Int) {
    fun up(n: Int = 1) = Vec3i(x, y + n, z)
    fun down(n: Int = 1) = Vec3i(x, y - n, z)
    operator fun plus(o: Vec3i) = Vec3i(x + o.x, y + o.y, z + o.z)
}

/**
 * The only thing a pathfinder needs from the world: whether a block is solid.
 * Implemented against a synthetic grid in tests, and against the live world by the
 * Fabric host — so a pathfinder is pure-JVM and fully unit-testable.
 */
fun interface BlockView {
    fun isSolid(pos: Vec3i): Boolean

    /**
     * Allocation-free overload keyed on primitive coordinates — the form the built-in A* calls in
     * its hot expansion loop. The default delegates to [isSolid] so every existing lambda / custom
     * [BlockView] keeps working unchanged; a host that can answer from primitives (e.g. the Fabric
     * world adapter) overrides this to avoid allocating a [Vec3i] per query.
     */
    fun isSolid(x: Int, y: Int, z: Int): Boolean = isSolid(Vec3i(x, y, z))
}

/**
 * Movement costs in *ticks* (20/sec), in the spirit of Baritone's model: walking a
 * block is ~4.63 ticks, a diagonal is √2× that, jumping up adds a penalty, and falling
 * costs per block dropped. They only need to be self-consistent for a search to find good
 * paths; the absolute values mirror real Minecraft movement so paths look natural. A
 * custom [Pathfinder] is free to reuse these or define its own.
 */
object Cost {
    const val WALK = 4.63
    const val DIAGONAL = WALK * 1.4142135
    const val STEP_UP = 3.0      // extra cost to jump up one block
    const val FALL_PER = 1.0     // per block fallen
    const val PARKOUR = 2.0      // extra cost to jump a one-block gap
}

/**
 * Tunables handed to a [Pathfinder] for a single search. A custom pathfinder may honour
 * or ignore these; the built-in [AStarPathfinder] respects both.
 *
 * @property maxFall  the greatest drop (in blocks) the agent will walk off.
 * @property maxNodes a safety cap on nodes expanded before the search gives up (returns null).
 */
data class PathParams(
    val maxFall: Int = 3,
    val maxNodes: Int = 20_000,
)

/**
 * Service-provider interface for block-grid path search — **the extension point for custom
 * pathfinding**. Implement this and register it with [Pathfinders.active] to replace the
 * built-in A* everywhere `goto` walks the player. Because it is a `fun interface`, the
 * simplest implementation is a lambda:
 *
 * ```kotlin
 * Pathfinders.active = Pathfinder { start, goal, view, params ->
 *     myAlgorithm(start, goal, view, params.maxFall)
 * }
 * ```
 *
 * ### Contract
 * `findPath` returns the list of **standable** block positions from [start] to [goal]
 * **inclusive**, where each position is one move (walk / diagonal / step-up / fall /
 * parkour) from the previous, or `null` when no route exists. "Standable" means solid
 * ground below and a clear feet+head column — the same notion [BlockView.isSolid] feeds.
 * The result is consumed by [PathExecutor], which drives the player waypoint by waypoint.
 *
 * Implementations should be pure (no Minecraft access) so they stay unit-testable — the
 * world is reached only through [BlockView].
 */
fun interface Pathfinder {
    fun findPath(start: Vec3i, goal: Vec3i, view: BlockView, params: PathParams): List<Vec3i>?
}

/** Convenience overload: search with the default [PathParams]. */
fun Pathfinder.findPath(start: Vec3i, goal: Vec3i, view: BlockView): List<Vec3i>? =
    findPath(start, goal, view, PathParams())

/**
 * The active [Pathfinder] that the navigation layer (`goto` / [dev.macromod.engine.action.Navigator])
 * routes through. Defaults to the built-in [AStarPathfinder]; assign your own to swap the
 * whole engine's pathfinding without touching the navigator:
 *
 * ```kotlin
 * Pathfinders.active = MyPathfinder()   // use a custom algorithm
 * Pathfinders.reset()                   // restore the built-in A*
 * ```
 *
 * `@Volatile` so a swap from one thread (e.g. a config/GUI thread) is visible to the
 * client tick thread that runs navigation.
 */
object Pathfinders {
    @Volatile
    var active: Pathfinder = AStarPathfinder()

    /** Restore the built-in A* pathfinder. */
    fun reset() {
        active = AStarPathfinder()
    }
}
