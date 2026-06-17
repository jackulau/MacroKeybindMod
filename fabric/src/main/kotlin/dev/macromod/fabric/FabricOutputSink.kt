//? if >=1.16 {
package dev.macromod.fabric

import dev.macromod.engine.action.OutputSink
import net.minecraft.client.Minecraft
// Text/Component construction is the second most divergent area. From MC 1.19 the text
// model is the `Component` interface with the static factory `Component.literal(String)`;
// before 1.19 it was the concrete `TextComponent(String)` constructor. Both live in the
// `net.minecraft.network.chat` package under Mojang mappings across the whole 1.16–1.21
// range, so only the construction differs. The chat-HUD chain
// (`minecraft.gui.getChat().addMessage(component)`) is uniform across all these versions.
//? if >=1.19 {
import net.minecraft.network.chat.Component
//?} else
/*import net.minecraft.network.chat.TextComponent*/
//? if >=1.19 {
import org.slf4j.Logger
//?} else
/*import org.apache.logging.log4j.Logger*/

/**
 * Turns engine [OutputSink] output into real Minecraft client effects:
 *
 *  - [chat]: text starting with `/` is sent to the server as a *command*, everything else as
 *    a *chat message*. This is the MOST version-divergent area (chat signing arrived in 1.19
 *    and the send methods moved from `LocalPlayer` to `ClientPacketListener` in 1.19.3), so
 *    the actual send is split into era branches below.
 *  - [log]: adds a line to the client chat HUD (local only). Construction of the text differs
 *    pre/post-1.19; the HUD chain itself is uniform.
 *
 * When there is no player/connection yet (main menu, mid-connect) we fall back to the mod
 * [logger] so output is never silently dropped.
 *
 * This whole class is Stonecutter-gated to >=1.16: the two oldest targets (1.14.4 / 1.15.2)
 * have no tick/keybind loop wired (see [MacroModClient]) and use a logging sink instead.
 */
class FabricOutputSink(private val logger: Logger) : OutputSink {

    override fun chat(message: String) {
        val mc = Minecraft.getInstance()
        val player = mc.player
        if (player == null) {
            logger.info("[chat] {}", message)
            return
        }
        val isCommand = message.startsWith("/")
        //? if >=1.19.3 {
        // 1.19.3+: send methods live on the ClientPacketListener (the connection). Commands
        // are sent WITHOUT the leading slash (sendCommand internally parses the raw command).
        val connection = mc.connection
        if (connection == null) {
            logger.info("[chat] {}", message)
            return
        }
        if (isCommand) {
            connection.sendCommand(message.substring(1))
        } else {
            connection.sendChat(message)
        }
        //?} else
        /*// Pre-1.19.3: a single `LocalPlayer.chat(String)` handles both chat and commands,
        // and commands KEEP their leading slash (there is no separate command path / signing).
        player.chat(message)*/
    }

    override fun log(message: String) {
        val mc = Minecraft.getInstance()
        //? if >=1.19 {
        val text = Component.literal(message)
        //?} else
        /*val text = TextComponent(message)*/
        // Uniform across 1.16–1.21 under Mojmap: gui (public field) -> getChat() -> addMessage.
        mc.gui.getChat().addMessage(text)
    }
}
//?}
