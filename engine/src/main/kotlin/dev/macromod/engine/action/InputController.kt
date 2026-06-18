package dev.macromod.engine.action

/**
 * Platform input — the engine's window into controlling the player. Like [OutputSink],
 * the pure engine has only a no-op; the Fabric host supplies an implementation that drives
 * Minecraft key bindings and player rotation. Keeping it an interface means the
 * `key`/`look`/`press` actions are ordinary engine actions and unit-testable with a
 * recording controller — no Minecraft needed.
 */
interface InputController {
    /** Press and release a logical key (e.g. "attack", "use", "jump") within one tick. */
    fun tap(key: String)

    /** Press and hold a logical key until [release]. */
    fun hold(key: String)

    /** Release a held key. */
    fun release(key: String)

    /** Set absolute player rotation (degrees). */
    fun look(yaw: Float, pitch: Float)

    /** Rotate by a delta (degrees). */
    fun turn(deltaYaw: Float, deltaPitch: Float)

    object NoOp : InputController {
        override fun tap(key: String) {}
        override fun hold(key: String) {}
        override fun release(key: String) {}
        override fun look(yaw: Float, pitch: Float) {}
        override fun turn(deltaYaw: Float, deltaPitch: Float) {}
    }
}
