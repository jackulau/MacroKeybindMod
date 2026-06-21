// A minimal in-game GUI to toggle automation modules. The Minecraft Screen API is the most
// version-divergent surface in the mod (PoseStack->GuiGraphics at 1.20, Button.builder, the
// 1.21.9 changes, etc.), so the GUI targets the 1.21.x line — the current Hypixel SkyBlock
// floor — and is feature-gated OUT on older versions (which still work via keybinds + config
// files). The whole file is gated: on <1.21 it compiles to an empty file.
//? if >=1.21 {
package dev.macromod.fabric.ui

import dev.macromod.engine.module.ModuleManager
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** Lists registered modules, each with a toggle button. Opened by a keybind in the bridge. */
class ModuleScreen(private val modules: ModuleManager) : Screen(Component.literal("MacroKeybindMod Modules")) {

    override fun init() {
        var y = 40
        for (module in modules.all()) {
            addRenderableWidget(
                Button.builder(label(module.name, module.enabled)) { button ->
                    modules.toggle(module.name)
                    button.message = label(module.name, modules.isEnabled(module.name))
                }.bounds(width / 2 - 100, y, 200, 20).build(),
            )
            y += 24
        }
        addRenderableWidget(
            Button.builder(Component.literal("Done")) { onClose() }
                .bounds(width / 2 - 100, y + 8, 200, 20).build(),
        )
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(graphics, mouseX, mouseY, delta)
        graphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFF.toInt())
    }

    override fun isPauseScreen(): Boolean = false

    private fun label(name: String, on: Boolean): Component =
        Component.literal("$name: ${if (on) "ON" else "OFF"}")
}
//?}
