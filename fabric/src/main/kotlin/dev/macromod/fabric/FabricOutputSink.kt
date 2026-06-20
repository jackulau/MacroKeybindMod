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
        // Sending chat/commands is the MOST version-divergent area, split into three eras as
        // SEPARATE single-line gates — deliberately NOT one `//?} else` block. A multi-line
        // `else` body whose first inner line is a `//` comment desyncs Stonecutter's comment
        // toggler and silently comments out the live statement (this exact bug shipped chat as a
        // dead no-op on 1.16.5/1.17.1/1.18.2/1.19.2). Each branch below is one self-contained line:
        //  - >=1.19.3: send methods moved to the ClientPacketListener (connection); commands are
        //    sent WITHOUT the leading slash (sendCommand parses the raw command).
        //  - 1.19..1.19.2: chat signing arrived and LocalPlayer.chat(String) was removed. Use
        //    chatSigned(msg, null) / commandSigned(cmd-without-slash, null) — null = no preview.
        //  - <1.19: a single LocalPlayer.chat(String) handles both; commands KEEP the slash.
        //? if >=1.19.3 {
        val connection = mc.connection
        if (connection == null) {
            logger.info("[chat] {}", message)
            return
        }
        if (isCommand) connection.sendCommand(message.substring(1)) else connection.sendChat(message)
        //?}
        //? if >=1.19 && <1.19.3 {
        /*if (isCommand) player.commandSigned(message.substring(1), null) else player.chatSigned(message, null)*/
        //?}
        //? if <1.19 {
        /*player.chat(message)*/
        //?}
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
