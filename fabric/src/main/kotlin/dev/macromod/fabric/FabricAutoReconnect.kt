//? if >=1.16 {
package dev.macromod.fabric

import dev.macromod.engine.module.ModuleManager
import dev.macromod.engine.module.modules.AutoReconnectModule
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.DisconnectedScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.ServerData
// ServerAddress moved from net.minecraft.client.multiplayer to ...multiplayer.resolver in 1.17
// (verified: 1.16.5 = old package, 1.17.1 = resolver). Source-of-truth (active version 1.21.1,
// >=1.17) uses the resolver package; the pre-1.17 branch (1.16.x) is the commented one and
// Stonecutter flips them per target version.
//? if >=1.17 {
import net.minecraft.client.multiplayer.resolver.ServerAddress
//?} else
/*import net.minecraft.client.multiplayer.ServerAddress*/
import java.lang.reflect.Modifier

/**
 * The live implementation of the **auto-reconnect** feature. When the
 * [AutoReconnectModule] toggle is enabled, this rejoins the last server a configurable delay
 * after a disconnect, up to a capped number of attempts. Like [FabricNavigator] it is a Fabric
 * host binding: the engine only carries the on/off + tunables (so it toggles through the same
 * GUI / keybind as every module), while the Minecraft-bound rejoin lives here.
 *
 * ## How it works
 *  - [tick] (from the bridge's `END_CLIENT_TICK`) tracks state:
 *      - **in a world** (`player != null`) — remember the current server ([Minecraft.getCurrentServer])
 *        and reset the attempt counter; nothing to do.
 *      - **on the disconnect screen** with the module enabled — arm a countdown of
 *        [AutoReconnectModule.delayTicks]; when it elapses, attempt a reconnect (counting toward
 *        [AutoReconnectModule.maxAttempts]). A failed attempt drops back to the disconnect screen,
 *        which re-arms the countdown until the cap is hit.
 *  - It only fires from a real [DisconnectedScreen], so quitting to the title or to single-player
 *    never triggers it.
 *
 * ## Why reflection for the rejoin
 * `ConnectScreen.startConnecting`'s arity grew across versions: 4 args (1.16–1.19), +`boolean`
 * quick-play (1.20), +nullable transfer-state (1.20.5+). Rather than gate the call per version,
 * we resolve the single static `void(Screen, …)` method by **shape** and pad the extra args —
 * one code path that compiles and runs on every supported version. The class + parameter types
 * are real compile-time references (so Loom remaps them correctly at runtime); only the method's
 * *arity* is discovered reflectively.
 */
class FabricAutoReconnect(private val modules: ModuleManager) {

    /** The server we were last connected to (captured while in-world), or null. */
    private var lastServer: ServerData? = null

    /** Ticks left before the next reconnect attempt; <0 means "not armed". */
    private var countdown = -1

    /** Consecutive reconnect attempts since the last successful connection. */
    private var attempts = 0

    /** Advance the auto-reconnect state machine by one client tick. */
    fun tick() {
        val mc = Minecraft.getInstance()

        // In a world: remember the server so we can rejoin it later, and reset attempt state.
        if (mc.player != null) {
            mc.currentServer?.let { lastServer = it }
            countdown = -1
            attempts = 0
            return
        }

        val module = modules.get("autoreconnect") as? AutoReconnectModule ?: return
        if (!module.enabled) { countdown = -1; return }
        val server = lastServer ?: return
        // Only auto-reconnect from a genuine disconnect — not the title/server-list/SP screens.
        if (mc.screen !is DisconnectedScreen) { countdown = -1; return }
        if (attempts >= module.maxAttempts) return

        if (countdown < 0) {            // just landed on the disconnect screen → arm the delay
            countdown = module.delayTicks
            return
        }
        if (countdown > 0) {            // waiting out the delay
            countdown--
            return
        }
        // Delay elapsed → attempt a reconnect.
        attempts++
        countdown = -1
        runCatching { reconnect(mc, server) }
    }

    private fun reconnect(mc: Minecraft, server: ServerData) {
        val address = ServerAddress.parseString(server.ip)
        val connect = ConnectScreen::class.java.methods.firstOrNull {
            Modifier.isStatic(it.modifiers) &&
                it.returnType == Void.TYPE &&
                it.parameterCount in 4..6 &&
                it.parameterTypes[0] == Screen::class.java
        } ?: return
        val args: Array<Any?> = when (connect.parameterCount) {
            4 -> arrayOf(mc.screen, mc, address, server)
            5 -> arrayOf(mc.screen, mc, address, server, false)          // + quickPlay
            6 -> arrayOf(mc.screen, mc, address, server, false, null)    // + quickPlay, transferState
            else -> return
        }
        connect.invoke(null, *args)
    }
}
//?}
