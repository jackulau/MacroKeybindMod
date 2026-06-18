package dev.macromod.engine.action

/**
 * Platform navigation — lets a macro ask the client to walk somewhere. Like [OutputSink]
 * and [InputController], the pure engine ships only a no-op; the Fabric host implements it
 * by running the [dev.macromod.pathfinding.Pathfinder] over the live world and driving the
 * [dev.macromod.pathfinding.PathExecutor] through the [InputController] each tick. Keeps the
 * `goto` action a normal, unit-testable engine action.
 */
interface Navigator {
    /** Begin navigating to a block position. Returns true if a path was found. */
    fun pathTo(x: Int, y: Int, z: Int): Boolean

    /** Cancel any active navigation. */
    fun stop()

    val isNavigating: Boolean

    object NoOp : Navigator {
        override fun pathTo(x: Int, y: Int, z: Int): Boolean = false
        override fun stop() {}
        override val isNavigating: Boolean get() = false
    }
}
