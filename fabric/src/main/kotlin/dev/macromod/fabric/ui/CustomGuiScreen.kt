// Renders a custom GUI built from the script's setlabel / setproperty state. Like the other
// screens this targets the 1.21.x line (the Screen API is too version-divergent below it); on
// <1.21 the whole file compiles to nothing and showgui falls back to visible feedback.
//? if >=1.21 {
package dev.macromod.fabric.ui

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * A read-only view of the custom-GUI state: the declared labels (rendered as `name: text`) and any
 * control properties (rendered as `control.property = value`), with a Done button to close.
 */
class CustomGuiScreen(
    screenTitle: String,
    private val labels: List<Pair<String, String>>,
    private val properties: List<Pair<String, String>>,
) : Screen(Component.literal(screenTitle)) {

    override fun init() {
        addRenderableWidget(
            Button.builder(Component.literal("Done")) { onClose() }
                .bounds(width / 2 - 100, height - 28, 200, 20).build(),
        )
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(graphics, mouseX, mouseY, delta)
        graphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFF.toInt())
        var y = 40
        for ((name, text) in labels) {
            graphics.drawString(font, "$name: $text", width / 2 - 150, y, 0xFFFFFF.toInt())
            y += 12
        }
        if (properties.isNotEmpty()) {
            y += 6
            for ((control, value) in properties) {
                graphics.drawString(font, "$control = $value", width / 2 - 150, y, 0xA0A0A0.toInt())
                y += 12
            }
        }
    }

    override fun isPauseScreen(): Boolean = false
}
//?}
