//? if >=1.16 {
package dev.macromod.fabric

import dev.macromod.engine.action.InputController
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft

/**
 * Drives real Minecraft input from the engine's [InputController] actions
 * (`key`/`keydown`/`keyup`/`press`/`look`/`turn`). It is the input counterpart to
 * [FabricOutputSink]: the pure engine only knows the [InputController] interface, and this
 * Fabric class is the live implementation that pushes [KeyMapping]s down and rotates the
 * client player.
 *
 * ## Logical key map
 * Engine scripts use logical names ("attack", "jump", "1".."9", …). These map onto the
 * game's own [KeyMapping]s on `Minecraft.getInstance().options`, so a tap is exactly as if
 * the player had pressed their bound key — rebinds in Controls are respected, and any code
 * gated on `keyXxx.isDown()`/`consumeClick()` sees it. Unknown names are ignored.
 *
 * The map is resolved lazily (on first use) because at mod-init time `Minecraft.getInstance()`
 * may not have built its `options` yet; by the time a macro fires (a key press / tick) it
 * always has. We cache it once `options` is available.
 *
 * ## One-tick taps
 * [tap] presses the key NOW and remembers it; the bridge calls [endClientTick] at the START of
 * each `END_CLIENT_TICK`, which releases everything tapped on the PREVIOUS tick — so the key
 * stays down across one full client tick (long enough for the player tick to poll `isDown()`)
 * before being let go, like a real keystroke. [hold]/[release] are sticky (no auto-release).
 *
 * ## Version divergence (Stonecutter)
 * The whole file is gated `>=1.16` — the same floor as [FabricOutputSink] and the keybind/tick
 * wiring in [MacroModClient]; on 1.14.4/1.15.2 the bridge has no tick loop and uses a logging
 * sink, so no input controller is constructed there. Within `>=1.16` only ONE thing differs:
 *
 *  - **Rotation write.** From 1.17 `Entity` exposes `setYRot(float)`/`setXRot(float)` (the
 *    `yRot`/`xRot` fields became private). On 1.16.x those fields are public and assigned
 *    directly. The previous-rotation fields `yRotO`/`xRotO` are public on every version and
 *    are always assigned (so the next render frame doesn't interpolate from the old angle and
 *    visibly snap). The source-of-truth/active version is 1.21.1 (>=1.17), so the setter branch
 *    is written live and the field-assignment branch is the commented one; Stonecutter flips
 *    them per target so the braces stay balanced on every version.
 *
 * Everything else — `Options` field names (`keyAttack`, `keyShift`, `keySwapOffhand`,
 * `keyHotbarSlots`, …), `KeyMapping.setDown(boolean)`, `Minecraft.getInstance().player` — is
 * identically named across the whole 1.16 → 1.21.11 range under Mojang mappings, so no further
 * gating is needed. (`keySneak`→`keyShift` and `keySwapHands`→`keySwapOffhand` were renamed at
 * 1.15/1.16, both below this floor.)
 */
class FabricInputController : InputController {

    /** name → game KeyMapping, resolved once `Minecraft.options` exists. Null until then. */
    private var keys: Map<String, KeyMapping>? = null

    /** Keys pressed by [tap] this tick, released on the next [endClientTick]. */
    private val pendingRelease = mutableSetOf<KeyMapping>()

    /**
     * Build (and cache) the logical-name → [KeyMapping] map from the game options. Returns an
     * empty map if `options` isn't ready yet (so calls before the title screen are harmless),
     * and does NOT cache in that case so a later call can populate it.
     */
    private fun keyMap(): Map<String, KeyMapping> {
        keys?.let { return it }
        val options = Minecraft.getInstance().options ?: return emptyMap()
        val map = HashMap<String, KeyMapping>()
        map["attack"] = options.keyAttack
        map["use"] = options.keyUse
        map["forward"] = options.keyUp
        map["back"] = options.keyDown
        map["left"] = options.keyLeft
        map["right"] = options.keyRight
        map["jump"] = options.keyJump
        map["sneak"] = options.keyShift
        map["sprint"] = options.keySprint
        map["drop"] = options.keyDrop
        map["inventory"] = options.keyInventory
        // Keys are stored lowercase because resolve() lowercases the lookup, so a script can
        // write "swapHands", "swaphands", or "SWAPHANDS" interchangeably.
        map["swaphands"] = options.keySwapOffhand
        map["pickitem"] = options.keyPickItem
        // Hotbar slots 1..9 → keyHotbarSlots[0..8]. Bounds-checked so an unexpectedly short
        // array (paranoia) can't throw.
        val hotbar = options.keyHotbarSlots
        for (i in 0 until minOf(9, hotbar.size)) {
            map[(i + 1).toString()] = hotbar[i]
        }
        keys = map
        return map
    }

    /** Resolve a logical key name to its game KeyMapping, or null if unknown / not ready. */
    private fun resolve(key: String): KeyMapping? = keyMap()[key.trim().lowercase()]

    override fun tap(key: String) {
        val mapping = resolve(key) ?: return
        mapping.setDown(true)
        pendingRelease.add(mapping)
    }

    override fun hold(key: String) {
        resolve(key)?.setDown(true)
    }

    override fun release(key: String) {
        val mapping = resolve(key) ?: return
        mapping.setDown(false)
        pendingRelease.remove(mapping)
    }

    /**
     * Called by the bridge at the START of each `END_CLIENT_TICK`: release the keys that [tap]
     * pressed on the PREVIOUS tick, so each tap lasts one full client tick. Held keys (via
     * [hold]) are untouched. No-op when nothing is queued.
     */
    fun endClientTick() {
        if (pendingRelease.isEmpty()) return
        for (mapping in pendingRelease) mapping.setDown(false)
        pendingRelease.clear()
    }

    override fun look(yaw: Float, pitch: Float) {
        val player = Minecraft.getInstance().player ?: return
        val clampedPitch = pitch.coerceIn(-90f, 90f)
        // Two independent `//? if` blocks (NOT if/else): a multi-line `else` body re-comments its
        // inner lines on flip (Stonecutter desync), so each branch is its own self-contained,
        // brace-balanced region instead — the same idiom wireKeybinds() uses for the 1.21.9 split.
        // Active/vcs version is 1.21.1 (>=1.17), so the setter branch is written live (source of
        // truth) and the field-assignment branch is the commented one; Stonecutter flips per target.
        //? if >=1.17 {
        player.setYRot(yaw)
        player.setXRot(clampedPitch)
        //?}
        //? if <1.17 {
        /*player.yRot = yaw
        player.xRot = clampedPitch*/
        //?}
        // Match prev-rotation so the next frame doesn't interpolate from the old angle and snap.
        // (`yRotO`/`xRotO` are public on every version, so this is un-gated.)
        player.yRotO = yaw
        player.xRotO = clampedPitch
    }

    override fun turn(deltaYaw: Float, deltaPitch: Float) {
        val player = Minecraft.getInstance().player ?: return
        // Reading yRot/xRot compiles on every >=1.16 target (the fields are public on 1.16.x and
        // still readable on 1.17+); only the WRITE differs, and that is delegated to look().
        look(player.yRot + deltaYaw, player.xRot + deltaPitch)
    }

    /** Keys currently held down by [toggleKey], so it can flip them back off deterministically. */
    private val toggledDown = mutableSetOf<KeyMapping>()

    // The hotbar `selected` slot: a public Inventory field through 1.21.4, made private with
    // getSelectedSlot()/setSelectedSlot(int) accessors at 1.21.5 (verified by javap on the mojmap
    // jars). `inv` is an inferred local so no Inventory import is needed (its package also moved
    // over the range). Source-of-truth is 1.21.1 (<1.21.5), so the field branch is written live and
    // the accessor branch is commented; Stonecutter flips them per target.
    override fun slot(index: Int) {
        val player = Minecraft.getInstance().player ?: return
        val inv = player.inventory
        val n = (index - 1).coerceIn(0, 8)
        //? if >=1.21.5 {
        /*inv.setSelectedSlot(n)*/
        //?}
        //? if <1.21.5 {
        inv.selected = n
        //?}
    }

    override fun scrollHotbar(delta: Int) {
        val player = Minecraft.getInstance().player ?: return
        val inv = player.inventory
        // Math.floorMod gives a correct positive modulus for negative deltas (wrap-around).
        //? if >=1.21.5 {
        /*inv.setSelectedSlot(Math.floorMod(inv.selectedSlot + delta, 9))*/
        //?}
        //? if <1.21.5 {
        inv.selected = Math.floorMod(inv.selected + delta, 9)
        //?}
    }

    override fun type(text: String) {
        // Type into the open screen's focused field (sign / anvil / book editor, …). charTyped took
        // (char, modifiers) through 1.21.8 and a CharacterEvent(codepoint, modifiers) record from
        // 1.21.9. No screen → nothing to type into.
        val screen = Minecraft.getInstance().screen ?: return
        for (c in text) {
            //? if >=1.21.9 {
            /*screen.charTyped(net.minecraft.client.input.CharacterEvent(c.code, 0))*/
            //?}
            //? if <1.21.9 {
            screen.charTyped(c, 0)
            //?}
        }
    }

    override fun toggleKey(key: String) {
        val mapping = resolve(key) ?: return
        if (toggledDown.remove(mapping)) mapping.setDown(false) else { mapping.setDown(true); toggledDown.add(mapping) }
    }
}
//?}
