package dev.macromod.fabric

import dev.macromod.engine.action.OutputSink
import dev.macromod.engine.macro.MacroBinding
import dev.macromod.engine.macro.MacroEngine
import dev.macromod.engine.macro.Trigger
import net.fabricmc.api.ClientModInitializer
// Logging facade differs by era: Fabric re-exposes SLF4J only from 1.19+. For 1.16.5 /
// 1.17.1 / 1.18.2 there is no guaranteed SLF4J on the classpath, so fall back to Log4j2,
// which Minecraft has always bundled. Stonecutter swaps the active branch per version; the
// `logger.info("..", arg)` call sites stay identical because both APIs accept `{}`
// placeholders. Source-of-truth is the active version (1.21.1, >=1.19) → SLF4J branch live.
//? if >=1.19 {
import org.slf4j.LoggerFactory
//?} else
/*import org.apache.logging.log4j.LogManager*/

// --- Fabric bridge imports ------------------------------------------------------------
// The keybind + tick wiring uses the v1 Fabric APIs, which only exist from the 1.16 era
// onward (fabric-key-binding-api-v1 / fabric-lifecycle-events-v1 shipped during the 1.15
// cycle but were not in 1.14.4 / 1.15.2's active life). On those two oldest versions the
// bridge degrades to a logging-only sink (the engine is still constructed + smoke-tested),
// so the mod keeps building everywhere. Everything below is feature-gated accordingly.
//? if >=1.16 {
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import com.mojang.blaze3d.platform.InputConstants
import org.lwjgl.glfw.GLFW
//?}

// Client chat-receive (onChat) is first-party in Fabric only from MC 1.19.3
// (fabric-message-api-v1). Older eras have no client-side receive event without a Mixin,
// so onChat firing is gated to >=1.19.3.
//? if >=1.19.3 {
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
//?}

/**
 * Client entry point and Fabric "bridge". Wires the pure-JVM [MacroEngine] to real
 * Minecraft input + output:
 *
 *  1. Proves the mod is loaded + the engine is shaded in (load-time smoke line).
 *  2. Owns a single [MacroEngine] and a [FabricOutputSink] (chat/command send + HUD log).
 *  3. Registers a demo keybind and, each client tick, fires the engine for that key
 *     (so a key press visibly runs a bound macro).
 *  4. Each tick fires the `onTick` event — but only when bindings exist (no per-tick spam).
 *  5. On modern versions (>=1.19.3) fires `onChat` from the client chat-receive event.
 *
 * Version divergence is handled with Stonecutter feature gates; the source-of-truth branch
 * is the active version (1.21.1). See [FabricOutputSink] for the chat/Text gates. Each gate
 * wraps a whole declaration or statement so the braces stay balanced on every version.
 */
class MacroModClient : ClientModInitializer {
    //? if >=1.19 {
    private val logger = LoggerFactory.getLogger("MacroMod")
    //?} else
    /*private val logger = LogManager.getLogger("MacroMod")*/

    /** The one engine instance for the whole client session. */
    private lateinit var engine: MacroEngine

    /** The sink that turns engine output into real chat / HUD lines (or logs as a fallback). */
    private lateinit var sink: OutputSink

    // The demo keybind + its GLFW code. Held nullable so the (un-gated) tick handler and
    // binding seeding can reference them on every version; only wired up on >=1.16.
    //? if >=1.16 {
    private var demoKey: KeyMapping? = null
    private val demoKeyCode = GLFW.GLFW_KEY_H

    // The live input controller (drives real KeyMappings + player rotation). Only constructed
    // on >=1.16 — the same floor as the tick/keybind loop; on 1.14.4/1.15.2 the engine keeps
    // its InputController.NoOp default. Held so wireTick() can release one-tick taps each tick.
    private val inputController = FabricInputController()
    //?}

    override fun onInitializeClient() {
        logger.info("MacroMod client initializing")

        sink = makeSink()
        engine = makeEngine()

        // Seed demo bindings so the wiring visibly does something in-game.
        seedDemoBindings()

        //? if >=1.17 {
        // Expose player state to scripts as %HEALTH% / %XPOS% / ... (env provider).
        registerPlayerEnv()
        //?}

        //? if >=1.16 {
        wireKeybinds()
        wireTick()
        //?}

        //? if >=1.19.3 {
        wireChatReceive()
        //?}

        // Load-time smoke: prove the engine is shaded in and runs end to end.
        engine.host.run("\$\${ log(\"MacroMod engine ready\") }\$\$", sink)

        logger.info("MacroMod client ready")
    }

    /** A logging-only [OutputSink], used on versions where no client output API is wired. */
    private inner class LoggingSink : OutputSink {
        override fun chat(message: String) { logger.info("[chat] {}", message) }
        override fun log(message: String) { logger.info("[log] {}", message) }
    }

    /**
     * Build the real [OutputSink]. On >=1.16 this is a [FabricOutputSink] (chat send + HUD);
     * on the two oldest versions (1.14.4 / 1.15.2) we have no tick/keybind loop to drive it,
     * so we keep a [LoggingSink] — the engine still compiles + runs at load time. Both
     * branches are single-line returns (the proven Stonecutter idiom) so the gate never
     * splits a brace pair.
     */
    private fun makeSink(): OutputSink {
        //? if >=1.16 {
        return FabricOutputSink(logger)
        //?} else
        /*return LoggingSink()*/
    }

    /**
     * Build the [MacroEngine], wiring the live [FabricInputController] so the `key`/`look`/
     * `turn` actions drive the real player. On 1.14.4/1.15.2 there is no input controller (no
     * tick loop to release taps), so the engine keeps its [dev.macromod.engine.action.InputController.NoOp]
     * default. Both branches are single-line returns (the proven Stonecutter idiom) so the gate
     * never splits a brace pair.
     */
    private fun makeEngine(): MacroEngine {
        //? if >=1.16 {
        return MacroEngine(input = inputController)
        //?} else
        /*return MacroEngine()*/
    }

    /**
     * Register demo bindings into the engine's registry:
     *  - the demo key (H) → a script that logs to the HUD AND taps `jump` and re-centers the
     *    view with `look(0,0)`, exercising the [FabricInputController] (key dispatch + input).
     *    Pressing H in-world should make the player hop and snap the camera to yaw/pitch 0.
     *  - `onTick` is intentionally NOT seeded here (so the per-tick fire stays a no-op until
     *    a user adds an onTick binding), but `onChat` is, to show event dispatch.
     */
    private fun seedDemoBindings() {
        //? if >=1.16 {
        engine.macros.add(
            MacroBinding(
                trigger = Trigger.Key(demoKeyCode),
                script = "\$\${ log(\"MacroMod: hotkey!\"); key(\"jump\"); look(0, 0) }\$\$",
                name = "demo-hotkey",
            ),
        )
        //?}
        //? if >=1.19.3 {
        engine.macros.add(
            MacroBinding(
                trigger = Trigger.Event("onChat"),
                script = "\$\${ log(\"MacroMod: saw a chat line\") }\$\$",
                name = "demo-onchat",
            ),
        )
        //?}
    }

    //? if >=1.16 {
    /**
     * Register the demo keybind with Fabric so it shows in Controls and can be rebound.
     *
     * The `KeyMapping` category argument changed type in 1.21.9: it was a translation-key
     * `String` ("category.macromod") through 1.21.8, and became a `KeyMapping.Category` record
     * from 1.21.9 onward. We use the built-in `KeyMapping.Category.MISC` on >=1.21.9 (no custom
     * category registration needed) and the String on older versions. Only the constructor's
     * last argument differs, so the gate wraps the whole call to keep it balanced.
     */
    private fun wireKeybinds() {
        // Two independent `//? if` blocks (not if/else) so each is a self-contained,
        // brace-balanced multi-line region — Stonecutter handles those cleanly, whereas a
        // multi-line `else` body can desync its comment markers.
        // NOTE on comment state: the active/vcs version is 1.21.1 (<1.21.9), so the String
        // branch is written uncommented here (source-of-truth) and the MISC branch is the
        // commented one. Stonecutter flips these per target version at generation time.
        //? if >=1.21.9 {
        /*val mapping = KeyMapping(
            "key.macromod.demo",
            InputConstants.Type.KEYSYM,
            demoKeyCode,
            KeyMapping.Category.MISC,
        )*/
        //?}
        //? if <1.21.9 {
        val mapping = KeyMapping(
            "key.macromod.demo",
            InputConstants.Type.KEYSYM,
            demoKeyCode,
            "category.macromod",
        )
        //?}
        demoKey = KeyBindingHelper.registerKeyBinding(mapping)
    }

    /**
     * Each client tick: drain demo-key presses (fireKey), and fire `onTick` — but only when
     * the registry actually has onTick bindings, so an empty config costs nothing per tick.
     */
    private fun wireTick() {
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            // Release taps from the PREVIOUS tick first, so a key("..") tapped last tick stayed
            // down through this tick's player processing (the player tick polls keyXxx.isDown()
            // before END_CLIENT_TICK fires) and is now let go — a clean one-tick press.
            inputController.endClientTick()

            val key = demoKey
            // consumeClick() returns true once per queued press (Mojmap, stable all eras).
            if (key != null) {
                while (key.consumeClick()) {
                    engine.fireKey(demoKeyCode, sink)
                }
            }
            if (engine.macros.forEvent("onTick").isNotEmpty()) {
                engine.fireEvent("onTick", sink)
            }
        }
    }
    //?}

    //? if >=1.19.3 {
    /** Fire `onChat` whenever the client receives a chat/game message (modern Fabric event). */
    private fun wireChatReceive() {
        ClientReceiveMessageEvents.GAME.register { _, _ ->
            if (engine.macros.forEvent("onChat").isNotEmpty()) {
                engine.fireEvent("onChat", sink)
            }
        }
        ClientReceiveMessageEvents.CHAT.register { _, _, _, _, _ ->
            if (engine.macros.forEvent("onChat").isNotEmpty()) {
                engine.fireEvent("onChat", sink)
            }
        }
    }
    //?}

    //? if >=1.17 {
    /**
     * Register a player [dev.macromod.engine.variable.EnvProvider] exposing live client-player
     * state. Names are matched raw (uppercase) so scripts read `%HEALTH%`, `%XPOS%`, etc.
     * Yaw/pitch use the `getYRot()`/`getXRot()` getters introduced in 1.17, so this is gated
     * to >=1.17 (older eras read the `yRot`/`xRot` fields and are simply not wired here).
     */
    private fun registerPlayerEnv() {
        engine.variables.addEnvProvider { name ->
            val player = Minecraft.getInstance().player ?: return@addEnvProvider null
            when (name.uppercase()) {
                "HEALTH" -> dev.macromod.engine.value.Value.Num(player.health.toInt())
                "XPOS" -> dev.macromod.engine.value.Value.Num(player.x.toInt())
                "YPOS" -> dev.macromod.engine.value.Value.Num(player.y.toInt())
                "ZPOS" -> dev.macromod.engine.value.Value.Num(player.z.toInt())
                "YAW" -> dev.macromod.engine.value.Value.Num(player.yRot.toInt())
                "PITCH" -> dev.macromod.engine.value.Value.Num(player.xRot.toInt())
                else -> null
            }
        }
    }
    //?}
}
