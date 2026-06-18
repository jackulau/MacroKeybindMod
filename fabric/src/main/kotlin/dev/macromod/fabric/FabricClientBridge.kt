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

/**
 * Fabric realization of the engine's [ClientBridge] (settings / world / HUD / query capabilities).
 *
 * v1 applies the cross-version-stable effect (player [respawn]) and surfaces every other action as
 * visible [feedback] (routed to the HUD/log sink), so each keyword is recognised and does something
 * observable on every supported version. Richer *live* mutation — option changes via `OptionInstance`,
 * registry sound playback, custom toasts, named GUI screens — is intentionally the next Fabric pass:
 * those APIs churn hard across 1.16→1.21 (Component@1.19, OptionInstance@1.18, the 1.21.x refactors)
 * and would need per-option Stonecutter gating. Keeping v1 churn-free guarantees the bridge compiles
 * on all 23 versions. Read queries ([WorldQuery]) are wired separately.
 */
class FabricClientBridge(
    private val feedback: (String) -> Unit,
    private val queryImpl: WorldQuery = WorldQuery.NoOp,
) : ClientBridge {

    override val settings = object : ClientSettings {
        override fun apply(name: String, args: List<String>) {
            feedback("[setting] $name ${args.joinToString(" ")}".trim())
        }
    }

    override val world = object : WorldActions {
        override fun respawn() { Minecraft.getInstance().player?.respawn() }
        override fun disconnect() { feedback("[world] disconnect requested") }
        override fun playSound(sound: String) { feedback("[playsound] $sound") }
        override fun placeSign(lines: List<String>) { feedback("[placesign] ${lines.joinToString(" | ")}") }
    }

    override val hud = object : Hud {
        override fun title(title: String, subtitle: String) {
            feedback(if (subtitle.isEmpty()) "[title] $title" else "[title] $title / $subtitle")
        }
        override fun toast(title: String, description: String) { feedback("[toast] $title $description".trim()) }
        override fun popup(message: String) { feedback("[popup] $message") }
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
