package dev.macromod.engine.action

/**
 * Platform capabilities beyond input/output/navigation — the engine's window into the rest of
 * the Minecraft client. Like [InputController]/[OutputSink], the pure engine ships only no-ops;
 * the Fabric host supplies real implementations. Bundling them in one [ClientBridge] keeps the
 * execution context tidy as the MC-bound action set grows (settings, world, HUD, queries).
 *
 * Every method has a default (no-op / empty), so a host implements only what it supports and
 * tests can supply a small recording fake.
 */

/** Client option / settings changes (`fov`, `gamma`, `volume`, `bind`, chat sizing, …). */
interface ClientSettings {
    /** Apply a named option change. [args] are the action's raw (expanded) arguments. */
    fun apply(name: String, args: List<String>) {}
    object NoOp : ClientSettings
}

/** World / player side-effect actions (`respawn`, `disconnect`, `playsound`, `placesign`). */
interface WorldActions {
    fun respawn() {}
    fun disconnect() {}
    fun playSound(sound: String) {}
    fun placeSign(lines: List<String>) {}
    object NoOp : WorldActions
}

/** On-screen feedback (`title`, `toast`, `popupmessage`, vanilla `gui` screens). */
interface Hud {
    fun title(title: String, subtitle: String) {}
    fun toast(title: String, description: String) {}
    fun popup(message: String) {}
    fun openGui(name: String) {}
    object NoOp : Hud
}

/** Read-only world / inventory queries (`getid`, `getslot`, `trace`, `pick`, …). */
interface WorldQuery {
    /** Block registry id at world coords (e.g. "minecraft:stone"), or "" if unknown/unloaded. */
    fun blockAt(x: Int, y: Int, z: Int): String = ""
    /** Hotbar/inventory slot index holding [item] (registry id or suffix match), or -1. */
    fun findSlot(item: String): Int = -1
    /** Registry id of the item in [slot], or "". */
    fun itemInSlot(slot: Int): String = ""
    /** Select the first of [items] found on the hotbar; returns whether one was selected. */
    fun pick(items: List<String>): Boolean = false
    /** Registry id of the block/entity the player is looking at within [distance], or "". */
    fun trace(distance: Int): String = ""
    object NoOp : WorldQuery
}

/** The bundle of MC-bound capabilities handed to actions via the execution context. */
interface ClientBridge {
    val settings: ClientSettings get() = ClientSettings.NoOp
    val world: WorldActions get() = WorldActions.NoOp
    val hud: Hud get() = Hud.NoOp
    val query: WorldQuery get() = WorldQuery.NoOp
    object NoOp : ClientBridge
}
