// In-game REPL console: type a MacroKeybindMod script line and run it against the live engine,
// with the captured output shown below. Like ModuleScreen, the Screen + EditBox APIs are too
// version-divergent to target older eras, so this is gated to the 1.21.x line (older versions still
// drive scripts via keybinds + config files). On <1.21 the whole file compiles to nothing.
//? if >=1.21 {
package dev.macromod.fabric.ui

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * A REPL screen: one text field + Run, with a scrolling tail of captured output. [onSubmit] is
 * supplied by the host; it compiles and runs the typed source against the live engine context
 * (variables / input / navigator / client) and returns the captured output lines.
 */
class ReplScreen(private val onSubmit: (String) -> List<String>) :
    Screen(Component.literal("MacroKeybindMod REPL")) {

    private lateinit var input: EditBox
    private val output = ArrayList<String>()

    override fun init() {
        input = EditBox(font, width / 2 - 150, 40, 300, 20, Component.literal("script"))
        input.setMaxLength(2000)
        addRenderableWidget(input)
        setInitialFocus(input)

        addRenderableWidget(
            Button.builder(Component.literal("Run")) { runInput() }
                .bounds(width / 2 - 150, 66, 145, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("Clear")) { output.clear() }
                .bounds(width / 2 + 5, 66, 145, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("Done")) { onClose() }
                .bounds(width / 2 - 100, height - 28, 200, 20).build(),
        )
    }

    private fun runInput() {
        val src = input.value.trim()
        if (src.isEmpty()) return
        output.add("> $src")
        output.addAll(onSubmit(src))
        while (output.size > 14) output.removeAt(0) // keep the last lines visible
        input.value = ""
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(graphics, mouseX, mouseY, delta)
        graphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFF.toInt())
        var y = 96
        for (line in output) {
            graphics.drawString(font, line, width / 2 - 150, y, 0xC0C0C0.toInt())
            y += 11
        }
    }

    override fun isPauseScreen(): Boolean = false
}
//?}
