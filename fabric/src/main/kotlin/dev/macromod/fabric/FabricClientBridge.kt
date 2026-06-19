//? if >=1.16 {
package dev.macromod.fabric

import dev.macromod.engine.action.ChatFilter
import dev.macromod.engine.action.ClientBridge
import dev.macromod.engine.action.ClientSettings
import dev.macromod.engine.action.Crafting
import dev.macromod.engine.action.GuiBuilder
import dev.macromod.engine.action.Hud
import dev.macromod.engine.action.WorldActions
import dev.macromod.engine.action.WorldQuery
import net.minecraft.client.Minecraft
// Component (the interface) exists 1.16+; only construction differs — Component.literal from 1.19,
// the TextComponent constructor before. See FabricOutputSink for the same split.
import net.minecraft.network.chat.Component
//? if <1.19 {
/*import net.minecraft.network.chat.TextComponent*/
//?}

/**
 * Fabric realization of the engine's [ClientBridge] (settings / world / HUD / query capabilities).
 *
 * Live now: player [respawn], video options ([FabricClientSettings]: fov/gamma/sensitivity/render
 * distance via `OptionInstance`, gated at 1.19), and HUD title/popup ([Gui]). The remaining churnier
 * effects — registry sound playback, custom toasts, named GUI screens, sound-volume options — surface
 * as visible [feedback] (routed to the HUD/log sink) so each keyword is recognised and does something
 * observable on every supported version rather than being silently dropped; those APIs churn hard
 * across 1.16→1.21 and are realized in later Fabric passes. Read queries ([WorldQuery]) wire separately.
 */
class FabricClientBridge(
    private val feedback: (String) -> Unit,
    private val queryImpl: WorldQuery = WorldQuery.NoOp,
) : ClientBridge {

    // Live option mutation (fov/gamma/sensitivity/renderdistance via OptionInstance, gated at 1.19);
    // the churnier options (sound volume, fog, camera, resolution, binds) fall back to feedback there.
    override val settings: ClientSettings = FabricClientSettings(feedback)

    override val world = object : WorldActions {
        override fun respawn() { Minecraft.getInstance().player?.respawn() }
        override fun disconnect() { feedback("[world] disconnect requested") }
        // Live client-side playback: resolve the id to a SoundEvent and play it through the player.
        // playSound(SoundEvent, vol, pitch) is stable across 1.16→1.21; only id→SoundEvent churns.
        override fun playSound(sound: String) {
            val player = Minecraft.getInstance().player
            if (player == null) { feedback("[playsound] $sound (no player)"); return }
            try {
                player.playSound(soundEventFor(sound), 1.0f, 1.0f)
            } catch (e: Exception) {
                feedback("[playsound] bad sound id: $sound")
            }
        }
        override fun placeSign(lines: List<String>) { feedback("[placesign] ${lines.joinToString(" | ")}") }
    }

    // Resolve a string id to a playable SoundEvent. Three API churns collide here, so the whole
    // construction is gated (the SoundEvent return type itself is stable across all 23 versions):
    //   - SoundEvent String-ctor -> createVariableRangeEvent() factory at 1.19.3
    //   - ResourceLocation String-ctor -> static parse() at 1.21
    //   - ResourceLocation renamed to Identifier at 1.21.11
    // Exactly one cell is active per version; the uncommented one matches the 1.21.1 source-of-truth.
    private fun soundEventFor(id: String): net.minecraft.sounds.SoundEvent {
        //? if <1.19.3 {
        /*return net.minecraft.sounds.SoundEvent(net.minecraft.resources.ResourceLocation(id))*/
        //?}
        //? if >=1.19.3 && <1.21 {
        /*return net.minecraft.sounds.SoundEvent.createVariableRangeEvent(net.minecraft.resources.ResourceLocation(id))*/
        //?}
        //? if >=1.21 && <1.21.11 {
        return net.minecraft.sounds.SoundEvent.createVariableRangeEvent(net.minecraft.resources.ResourceLocation.parse(id))
        //?}
        //? if >=1.21.11 {
        /*return net.minecraft.sounds.SoundEvent.createVariableRangeEvent(net.minecraft.resources.Identifier.parse(id))*/
        //?}
    }

    private fun text(s: String): Component {
        //? if >=1.19 {
        return Component.literal(s)
        //?}
        //? if <1.19 {
        /*return TextComponent(s)*/
        //?}
    }

    override val hud = object : Hud {
        // Real title/subtitle overlay + action-bar popup; toast (custom Toast system) + named GUI
        // stay as feedback for now (churnier; documented).
        override fun title(title: String, subtitle: String) {
            // Gui.setTitle/setSubtitle exist from 1.17; on 1.16.x fall back to visible feedback.
            //? if >=1.17 {
            val gui = Minecraft.getInstance().gui
            gui.setTitle(text(title))
            if (subtitle.isNotEmpty()) gui.setSubtitle(text(subtitle))
            //?}
            //? if <1.17 {
            /*feedback(if (subtitle.isEmpty()) "[title] $title" else "[title] $title / $subtitle")*/
            //?}
        }
        override fun toast(title: String, description: String) { feedback("[toast] $title $description".trim()) }
        override fun popup(message: String) { Minecraft.getInstance().gui.setOverlayMessage(text(message), false) }
        override fun openGui(name: String) { feedback("[gui] $name") }
    }

    override val chatFilter = object : ChatFilter {
        override fun setEnabled(enabled: Boolean) { feedback("[chatfilter] ${if (enabled) "on" else "off"}") }
        override fun filter() { feedback("[filter] message suppressed") }
        override fun modify(message: String) { feedback("[modify] $message") }
    }

    override val crafting = object : Crafting {
        override fun craft(item: String, amount: Int, wait: Boolean) { feedback("[craft] $amount x $item${if (wait) " (wait)" else ""}") }
        override fun clearCrafting() { feedback("[clearcrafting]") }
        override fun setSlotItem(item: String, slot: Int, amount: Int) { feedback("[setslotitem] $amount x $item -> slot $slot") }
        override fun slotClick(slot: Int, button: Int, shift: Boolean) { feedback("[slotclick] slot $slot button $button${if (shift) " +shift" else ""}") }
    }

    override val guiBuilder = object : GuiBuilder {
        override fun showGui(screen: String) { feedback("[showgui] $screen") }
        override fun bindGui(slot: Int, screen: String) { feedback("[bindgui] slot $slot -> $screen") }
        override fun setLabel(name: String, text: String) { feedback("[setlabel] $name = $text") }
        override fun setProperty(control: String, property: String, value: String) { feedback("[setproperty] $control.$property = $value") }
    }

    override val query get() = queryImpl
}
//?}
