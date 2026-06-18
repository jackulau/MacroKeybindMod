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

    /** Select hotbar slot [index] (1-9). */
    fun slot(index: Int) {}

    /** Scroll the hotbar selection by [delta] slots (negative = towards slot 1). */
    fun scrollHotbar(delta: Int) {}

    /** Type [text] as key input (the host injects one key per tick). */
    fun type(text: String) {}

    /** Toggle a logical key's held state (down if up, up if down). */
    fun toggleKey(key: String) {}

    object NoOp : InputController {
        override fun tap(key: String) {}
        override fun hold(key: String) {}
        override fun release(key: String) {}
        override fun look(yaw: Float, pitch: Float) {}
        override fun turn(deltaYaw: Float, deltaPitch: Float) {}
    }
}
