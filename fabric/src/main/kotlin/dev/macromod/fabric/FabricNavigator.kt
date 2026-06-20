//? if >=1.16 {
package dev.macromod.fabric

import dev.macromod.engine.action.Navigator
import dev.macromod.pathfinding.BlockView
import dev.macromod.pathfinding.PathExecutor
import dev.macromod.pathfinding.Pathfinders
import dev.macromod.pathfinding.Vec3i
import dev.macromod.pathfinding.findPath
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos

/**
 * The live implementation of the engine's [Navigator]: it runs the pure-JVM
 * [Pathfinder] over the real client world and drives the resulting [PathExecutor] through
 * the [FabricInputController] each client tick, so the `goto(x,y,z)` / `stopnav` actions
 * actually walk the player.
 *
 * The pure engine only knows the [Navigator] interface; this is the Fabric host binding,
 * the navigation counterpart to [FabricOutputSink] (output) and [FabricInputController]
 * (input). Keeping it here means `goto` stays an ordinary, unit-tested engine action while
 * all Minecraft-bound logic lives in the bridge.
 *
 * ## How it walks
 *  - [pathTo] snaps the player's feet to a block position ([net.minecraft.world.entity.Entity.blockPosition],
 *    which floors the entity position to a [BlockPos]), runs the A\* [Pathfinder] over a
 *    [worldView] of the live level, and — if a route is found — stores it with a fresh
 *    [PathExecutor] and flips [isNavigating] on. No path → returns false, nothing changes.
 *  - [tick] (called from the bridge's `END_CLIENT_TICK`) reads the player's current block
 *    position, asks the [PathExecutor] for this tick's [dev.macromod.pathfinding.MovementCommand],
 *    faces that yaw and holds exactly the requested movement keys (releasing any held key the
 *    command no longer wants, e.g. dropping `jump` once a climb is done). When the executor is
 *    finished (null step) it stops.
 *  - [stop] clears the route and releases every movement key it was holding.
 *
 * ## World view & solidity
 * [worldView] is a [BlockView] over `Minecraft.getInstance().level`. A block counts as solid
 * when [net.minecraft.world.level.block.state.BlockState.isCollisionShapeFullBlock] is true —
 * i.e. it is a *full collidable block* (dirt, stone, …) and not a slab, stair, torch, or air.
 * This is exactly the notion the pathfinder needs (it requires full ground under the feet and
 * a clear feet+head column), and crucially it is a single, **non-deprecated, identically named
 * instance method on `BlockStateBase` across the whole 1.16 → 1.21.x range** under Mojmap, so
 * no per-era gating is needed inside this file. (`isSolid()` / `blocksMotion()` moved off
 * `Material` onto the state at ~1.20, and `Material` was removed then — using the collision
 * shape sidesteps that boundary entirely.) Unloaded chunks read as **solid** so the search
 * never paths into terrain it cannot see — checked via `level.hasChunk(chunkX, chunkZ)`, which
 * `ClientLevel` inherits from `LevelAccessor` on every version.
 *
 * ## Version divergence (Stonecutter)
 * The whole file is gated `>=1.16` — the same floor as [FabricInputController] and
 * [FabricOutputSink]; on 1.14.4 / 1.15.2 the bridge has no tick loop, so the engine keeps its
 * [Navigator.NoOp] default there. Within `>=1.16` every Minecraft API used here
 * (`Minecraft.getInstance().level`, `Level.getBlockState`, `LevelAccessor.hasChunk`,
 * `Entity.blockPosition`, `BlockPos(int,int,int)` / `getX/getY/getZ`,
 * `BlockState.isCollisionShapeFullBlock`) is identically named and present, so there are no
 * inner gates — unlike the rotation-write split in [FabricInputController].
 */
class FabricNavigator(private val input: FabricInputController) : Navigator {

    /** All keys this navigator may press, so [stop] / [tick] can release stale ones. */
    private val movementKeys = setOf("forward", "back", "left", "right", "jump", "sprint", "sneak")

    /** The active route's executor, or null when not navigating. */
    private var executor: PathExecutor? = null

    /** Keys currently held by the navigator, so we only release what we pressed. */
    private val held = mutableSetOf<String>()

    override val isNavigating: Boolean get() = executor != null

    /**
     * A [BlockView] over the live client level. Returns solid for unloaded chunks and when
     * there is no level yet (main menu / mid-connect) so the pathfinder never walks into the
     * unknown; otherwise true iff the block is a full collidable block.
     */
    val worldView = object : BlockView {
        // A* queries this thousands of times per search, so reuse one mutable position rather than
        // allocating a BlockPos per check — the search then allocates no BlockPos at all. Safe
        // because pathfinding is single-threaded (pathTo runs findPath synchronously on the client
        // thread, worldView is queried only there, never re-entrantly) and neither getBlockState nor
        // isCollisionShapeFullBlock retains the position — the same MutableBlockPos-in-a-loop pattern
        // vanilla MC uses for collision scans.
        private val cursor = BlockPos.MutableBlockPos()

        override fun isSolid(pos: Vec3i): Boolean = isSolid(pos.x, pos.y, pos.z)

        // Primitive override: answer straight from coords, allocating neither a Vec3i nor a BlockPos.
        override fun isSolid(x: Int, y: Int, z: Int): Boolean {
            val level = Minecraft.getInstance().level ?: return true
            if (!level.hasChunk(x shr 4, z shr 4)) return true
            val blockPos = cursor.set(x, y, z)
            return level.getBlockState(blockPos).isCollisionShapeFullBlock(level, blockPos)
        }
    }

    /** The player's current feet position as a [Vec3i], or null when there is no player. */
    private fun playerPos(): Vec3i? {
        val player = Minecraft.getInstance().player ?: return null
        val bp = player.blockPosition() // floors the entity position to a BlockPos (feet)
        return Vec3i(bp.x, bp.y, bp.z)
    }

    override fun pathTo(x: Int, y: Int, z: Int): Boolean {
        val start = playerPos() ?: return false
        // Route through the swappable pathfinder registry — a user can replace the default A*
        // by assigning their own implementation to Pathfinders.active (see the engine SPI).
        val path = Pathfinders.active.findPath(start, Vec3i(x, y, z), worldView) ?: return false
        executor = PathExecutor(path)
        return true
    }

    /**
     * Advance navigation by one client tick. Reads the live player position, gets this tick's
     * movement command, faces it and holds exactly its keys (releasing any the command dropped).
     * Stops when the executor is done or the player is gone. No-op when not navigating.
     */
    fun tick() {
        val exec = executor ?: return
        val current = playerPos() ?: run { stop(); return }

        val command = exec.nextStep(current)
        if (command == null || exec.isDone) {
            stop()
            return
        }

        input.look(command.yaw, 0f)
        // Hold the keys this tick wants; release any movement key we were holding that it no
        // longer wants (e.g. let go of "jump" once the climb is finished).
        for (key in command.keys) {
            if (held.add(key)) input.hold(key)
        }
        val stale = held - command.keys
        for (key in stale) {
            input.release(key)
            held.remove(key)
        }
    }

    override fun stop() {
        executor = null
        releaseAll()
    }

    /** Release every movement key the navigator might be holding. */
    private fun releaseAll() {
        for (key in if (held.isEmpty()) movementKeys else held.toSet()) input.release(key)
        held.clear()
    }
}
//?}
