package dev.macromod.fabric

import dev.macromod.engine.action.OutputSink
import dev.macromod.engine.macro.MacroBinding
import dev.macromod.engine.macro.MacroEngine
import dev.macromod.engine.macro.Trigger
import dev.macromod.engine.module.ModuleContext
import dev.macromod.engine.module.ModuleManager
import dev.macromod.engine.module.modules.AutoClicker
import dev.macromod.engine.module.modules.AutoReconnectModule
import dev.macromod.engine.module.modules.FailsafeModule
import dev.macromod.engine.module.modules.FarmModule
import dev.macromod.engine.module.modules.FishingModule
import dev.macromod.engine.module.modules.RowFarmModule
import dev.macromod.engine.value.Value
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
import net.minecraft.world.entity.EquipmentSlot
import com.mojang.blaze3d.platform.InputConstants
import org.lwjgl.glfw.GLFW
//?}

// Client chat-receive (onChat) is first-party in Fabric only from MC 1.19.3
// (fabric-message-api-v1). Older eras have no client-side receive event without a Mixin,
// so onChat firing is gated to >=1.19.3.
//? if >=1.19.3 {
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
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
    private val logger = LoggerFactory.getLogger("MacroKeybindMod")
    //?} else
    /*private val logger = LogManager.getLogger("MacroKeybindMod")*/

    /** The one engine instance for the whole client session. */
    private lateinit var engine: MacroEngine

    /** The sink that turns engine output into real chat / HUD lines (or logs as a fallback). */
    private lateinit var sink: OutputSink

    /** Shared chat-filter state for onFilterableChat (read by the handler, set by filter/modify). */
    private val chatFilter = FabricChatFilter(feedback = { sink.log(it) })

    /** Routes the config(name) action to a profile switch + onConfigChange (import/unimport feedback). */
    private val configController = FabricConfigController(
        onSwitch = { name ->
            engine.configs.switchTo(name)
            if (engine.macros.hasEvent("onConfigChange")) engine.fireEvent("onConfigChange", sink)
        },
        feedback = { sink.log(it) },
    )

    /** Toggleable automation modules (auto-clicker, farm, …); ticked each client tick. */
    private val modules = ModuleManager()
    private var moduleTick = 0L

    // The demo keybind + its GLFW code. Held nullable so the (un-gated) tick handler and
    // binding seeding can reference them on every version; only wired up on >=1.16.
    //? if >=1.16 {
    private var demoKey: KeyMapping? = null
    private val demoKeyCode = GLFW.GLFW_KEY_H

    // A second demo key (G) that fires a `goto(...)` toward a spot a few blocks ahead of where
    // the player is standing, exercising the live FabricNavigator end to end. Built at press
    // time from the current position so it always targets a loaded, nearby block.
    private var navKey: KeyMapping? = null
    private val navKeyCode = GLFW.GLFW_KEY_G

    // GUI key (RIGHT_SHIFT) — opens the module-toggle screen. The screen only exists on
    // >=1.21 (see ModuleScreen); on 1.16–1.20.6 the key is registered but opening is a no-op.
    private var guiKey: KeyMapping? = null
    private val guiKeyCode = GLFW.GLFW_KEY_RIGHT_SHIFT

    // Auto-reconnect toggle key (numpad 5) — flips the AutoReconnectModule on/off in-game, so it
    // can be toggled even on versions without the module GUI (<1.21). Registered on >=1.16.
    private var autoReconnectKey: KeyMapping? = null
    private val autoReconnectKeyCode = GLFW.GLFW_KEY_KP_5

    // REPL console key (numpad 6) — opens the in-game script console (>=1.21; no-op on older).
    private var replKey: KeyMapping? = null
    private val replKeyCode = GLFW.GLFW_KEY_KP_6

    // The live input controller (drives real KeyMappings + player rotation). Only constructed
    // on >=1.16 — the same floor as the tick/keybind loop; on 1.14.4/1.15.2 the engine keeps
    // its InputController.NoOp default. Held so wireTick() can release one-tick taps each tick.
    private val inputController = FabricInputController()

    // The live navigator (runs the A* Pathfinder over the world and drives the player along it).
    // Same >=1.16 floor as the input controller; on 1.14.4/1.15.2 the engine keeps its
    // Navigator.NoOp default. Held so wireTick() can advance it each client tick (tick()).
    private val navigator = FabricNavigator(inputController)

    // The auto-reconnect handler — rejoins the last server on disconnect when the
    // AutoReconnectModule toggle is on. Reads the toggle/config from the shared ModuleManager;
    // advanced each client tick (tick()). Same >=1.16 floor as the rest of the bridge.
    private val autoReconnect = FabricAutoReconnect(modules)

    // Polled-event state: detect join/leave (player presence) + death (alive->dead) transitions
    // each tick, so onJoinGame/onLeaveGame/onDeath fire without a networking-API dependency.
    private var wasInGame = false
    private var wasDead = false
    // Previous-tick baselines for change-watcher events (-1 / "" = not yet sampled this session).
    private var prevHealth = -1
    private var prevHunger = -1
    private var prevLevel = -1
    private var prevHeldId = ""
    private var prevAir = -1
    private var prevTotalXp = -1
    private var prevSlot = -1
    private var prevRaining = -1 // -1 unset, 0 clear, 1 raining
    private var prevDimension = ""
    private var prevArmor = -1
    private var prevArmorDur = -1
    private var prevHeldDur = -1
    private var prevInvCount = -1
    private var prevOnline = -1
    private var prevScreen = ""
    private var prevGameMode = ""
    private var prevConfigName = "default" // active config profile (per-server switching)
    //?}

    override fun onInitializeClient() {
        logger.info("MacroKeybindMod client initializing")

        sink = makeSink()
        engine = makeEngine()

        // Seed demo bindings so the wiring visibly does something in-game.
        seedDemoBindings()

        //? if >=1.17 {
        // Expose player state to scripts as %HEALTH% / %XPOS% / ... (env provider).
        registerPlayerEnv()
        // Host iterators for foreach: players / hotbar / inventory.
        registerIterators()
        //?}

        //? if >=1.16 {
        registerModules()
        wireKeybinds()
        wireTick()
        //?}

        //? if >=1.19.3 {
        wireChatReceive()
        //?}

        // Load-time smoke: prove the engine is shaded in and runs end to end.
        engine.host.run("\$\${ log(\"MacroKeybindMod engine ready\") }\$\$", sink)

        logger.info("MacroKeybindMod client ready")
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
     * `turn` actions drive the real player, and the live [FabricNavigator] so `goto`/`stopnav`
     * walk it. On 1.14.4/1.15.2 there is neither (no tick loop to drive them), so the engine
     * keeps its [dev.macromod.engine.action.InputController.NoOp] /
     * [dev.macromod.engine.action.Navigator.NoOp] defaults. Both branches are single-line returns
     * (the proven Stonecutter idiom) so the gate never splits a brace pair.
     */
    private fun makeEngine(): MacroEngine {
        //? if >=1.16 {
        return MacroEngine(input = inputController, navigator = navigator, client = FabricClientBridge(feedback = { sink.log(it) }, queryImpl = FabricWorldQuery(), chatFilterImpl = chatFilter, configImpl = configController))
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
                script = "\$\${ log(\"MacroKeybindMod: hotkey!\"); key(\"jump\"); look(0, 0) }\$\$",
                name = "demo-hotkey",
            ),
        )
        //?}
        //? if >=1.19.3 {
        engine.macros.add(
            MacroBinding(
                trigger = Trigger.Event("onChat"),
                script = "\$\${ log(\"MacroKeybindMod: saw a chat line\") }\$\$",
                name = "demo-onchat",
            ),
        )
        //?}
        //? if >=1.16 {
        // Demo per-server profile: joining this server switches to the "skyblock" profile (its own
        // keybind layout) and fires onConfigChange — mirrors MKB's per-server configs. The binds above
        // live in the default profile (active in single-player / unmapped servers).
        engine.configs.mapServer("hypixel.net", "skyblock")
        engine.configs.config("skyblock").registry.add(
            MacroBinding(
                trigger = Trigger.Key(demoKeyCode),
                script = "\$\${ log(\"MacroKeybindMod: skyblock profile hotkey\") }\$\$",
                name = "skyblock-hotkey",
            ),
        )
        //?}
    }

    /** Register the built-in automation modules (disabled until toggled by a GUI/command). */
    private fun registerModules() {
        modules.register(AutoClicker())
        modules.register(FarmModule())
        modules.register(FishingModule())
        modules.register(RowFarmModule())
        // Auto-reconnect (off by default) — toggled via the GUI / keybind; rejoin driven by autoReconnect.
        modules.register(AutoReconnectModule())
        // Failsafe guards the automation modules — disables them on low health.
        modules.register(FailsafeModule(modules, listOf("autoclicker", "farm", "fishing", "rowfarm")))
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

        // The navigation demo key (G) — same per-version category split as above.
        //? if >=1.21.9 {
        /*val navMapping = KeyMapping(
            "key.macromod.goto",
            InputConstants.Type.KEYSYM,
            navKeyCode,
            KeyMapping.Category.MISC,
        )*/
        //?}
        //? if <1.21.9 {
        val navMapping = KeyMapping(
            "key.macromod.goto",
            InputConstants.Type.KEYSYM,
            navKeyCode,
            "category.macromod",
        )
        //?}
        navKey = KeyBindingHelper.registerKeyBinding(navMapping)

        // The GUI key (RIGHT_SHIFT) — same per-version category split as the keys above.
        //? if >=1.21.9 {
        /*val guiMapping = KeyMapping(
            "key.macromod.gui",
            InputConstants.Type.KEYSYM,
            guiKeyCode,
            KeyMapping.Category.MISC,
        )*/
        //?}
        //? if <1.21.9 {
        val guiMapping = KeyMapping(
            "key.macromod.gui",
            InputConstants.Type.KEYSYM,
            guiKeyCode,
            "category.macromod",
        )
        //?}
        guiKey = KeyBindingHelper.registerKeyBinding(guiMapping)

        // The auto-reconnect toggle key (numpad 5) — same per-version category split as above.
        //? if >=1.21.9 {
        /*val reconnectMapping = KeyMapping(
            "key.macromod.autoreconnect",
            InputConstants.Type.KEYSYM,
            autoReconnectKeyCode,
            KeyMapping.Category.MISC,
        )*/
        //?}
        //? if <1.21.9 {
        val reconnectMapping = KeyMapping(
            "key.macromod.autoreconnect",
            InputConstants.Type.KEYSYM,
            autoReconnectKeyCode,
            "category.macromod",
        )
        //?}
        autoReconnectKey = KeyBindingHelper.registerKeyBinding(reconnectMapping)

        // The REPL console key (numpad 6) — same per-version category split as above.
        //? if >=1.21.9 {
        /*val replMapping = KeyMapping(
            "key.macromod.repl",
            InputConstants.Type.KEYSYM,
            replKeyCode,
            KeyMapping.Category.MISC,
        )*/
        //?}
        //? if <1.21.9 {
        val replMapping = KeyMapping(
            "key.macromod.repl",
            InputConstants.Type.KEYSYM,
            replKeyCode,
            "category.macromod",
        )
        //?}
        replKey = KeyBindingHelper.registerKeyBinding(replMapping)
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

            // Advance navigation: if a goto() is active, this faces the next waypoint and holds
            // the movement keys for this tick (or stops + releases them when the path is done).
            // Uses hold()/release() (sticky), independent of the one-tick tap mechanism above.
            navigator.tick()

            // Advance auto-reconnect: rejoins the last server after a disconnect when enabled.
            autoReconnect.tick()

            // Resume any wait-suspended macros whose delay has elapsed (async runner).
            engine.tickWaits()

            // Tick automation modules — enabled ones (auto-clicker, farm, …) act this tick.
            modules.tick(
                ModuleContext(
                    tick = moduleTick++,
                    input = inputController,
                    output = sink,
                    navigator = navigator,
                    registry = engine.variables,
                ),
            )

            val key = demoKey
            // consumeClick() returns true once per queued press (Mojmap, stable all eras).
            if (key != null) {
                while (key.consumeClick()) {
                    engine.fireKey(demoKeyCode, sink)
                }
            }
            // G: run a goto() toward a spot ~5 blocks ahead (+Z) of the player's feet, built at
            // press time so it always targets a loaded, nearby block — exercises FabricNavigator.
            val nav = navKey
            if (nav != null) {
                while (nav.consumeClick()) {
                    val player = Minecraft.getInstance().player ?: continue
                    val feet = player.blockPosition()
                    val script = "\$\${ goto(${feet.x}, ${feet.y}, ${feet.z + 5}) }\$\$"
                    engine.host.run(script, sink, navigator = navigator)
                }
            }
            // RIGHT_SHIFT: open the module-toggle GUI (>=1.21; no-op on older).
            val gui = guiKey
            if (gui != null) {
                while (gui.consumeClick()) {
                    openModuleScreen()
                }
            }
            // KP_5: toggle auto-reconnect on/off and report the new state to the HUD.
            val reconnectToggle = autoReconnectKey
            if (reconnectToggle != null) {
                while (reconnectToggle.consumeClick()) {
                    modules.toggle("autoreconnect")
                    sink.log("Auto-reconnect: " + if (modules.isEnabled("autoreconnect")) "ON" else "OFF")
                }
            }
            // KP_6: open the REPL console (>=1.21; no-op on older).
            val repl = replKey
            if (repl != null) {
                while (repl.consumeClick()) {
                    openReplScreen()
                }
            }
            if (engine.macros.hasEvent("onTick")) {
                engine.fireEvent("onTick", sink)
            }
            pollEvents()
        }
    }

    /**
     * Fire client-state events from polled transitions each tick (no networking-API dependency):
     * presence (join/leave), death, and change-watchers for health/damage, food, experience level,
     * and the held item. Baselines reset on leave so a re-join never fires spurious change events.
     */
    private fun pollEvents() {
        val player = Minecraft.getInstance().player
        val inGame = player != null
        if (inGame != wasInGame) {
            if (inGame) {
                // Switch to the joined server's config profile; fire onConfigChange if it changed.
                val cfg = engine.configs.switchToServer(Minecraft.getInstance().currentServer?.ip)
                if (cfg.name != prevConfigName) {
                    prevConfigName = cfg.name
                    fireIfBound("onConfigChange")
                }
            }
            fireIfBound(if (inGame) "onJoinGame" else "onLeaveGame")
            wasInGame = inGame
        }
        // GUI/screen change — only sample the (allocating) screen name when a macro listens.
        if (engine.macros.hasEvent("onShowGui")) {
            val screen = Minecraft.getInstance().screen?.javaClass?.simpleName ?: ""
            if (screen != prevScreen) {
                if (screen.isNotEmpty()) engine.fireEvent("onShowGui", sink)
                prevScreen = screen
            }
        } else {
            prevScreen = ""
        }
        if (player != null) {
            val dead = !player.isAlive
            if (dead && !wasDead) fireIfBound("onDeath")
            wasDead = dead

            val health = player.health.toInt()
            if (prevHealth >= 0 && health != prevHealth) {
                fireIfBound("onHealthChange")
                if (health < prevHealth) fireIfBound("onDamage")
            }
            prevHealth = health

            val hunger = player.foodData.foodLevel
            if (prevHunger >= 0 && hunger != prevHunger) fireIfBound("onFoodChange")
            prevHunger = hunger

            val level = player.experienceLevel
            if (prevLevel >= 0 && level != prevLevel) fireIfBound("onLevelChange")
            prevLevel = level

            val totalXp = player.totalExperience
            if (prevTotalXp >= 0 && totalXp != prevTotalXp) fireIfBound("onXPChange")
            prevTotalXp = totalXp

            val air = player.airSupply
            if (prevAir >= 0 && air != prevAir) fireIfBound("onOxygenChange")
            prevAir = air

            // Expensive: hoverName renders a Component — only when a macro listens.
            if (engine.macros.hasEvent("onHeldItemChange")) {
                val heldId = player.mainHandItem.hoverName.string
                if (prevHeldId.isNotEmpty() && heldId != prevHeldId) engine.fireEvent("onHeldItemChange", sink)
                prevHeldId = heldId
            } else {
                prevHeldId = ""
            }

            // selected hotbar slot (accessor privatised at 1.21.5)
            //? if >=1.21.5 {
            /*val slot = player.inventory.getSelectedSlot()*/
            //?}
            //? if <1.21.5 {
            val slot = player.inventory.selected
            //?}
            if (prevSlot >= 0 && slot != prevSlot) fireIfBound("onInventorySlotChange")
            prevSlot = slot

            val currentLevel = Minecraft.getInstance().level
            val raining = if (currentLevel?.isRaining == true) 1 else 0
            if (prevRaining >= 0 && raining != prevRaining) fireIfBound("onWeatherChange")
            prevRaining = raining

            if (engine.macros.hasEvent("onWorldChange")) {
                val dim = currentLevel?.dimension()?.toString() ?: ""
                if (prevDimension.isNotEmpty() && dim != prevDimension) engine.fireEvent("onWorldChange", sink)
                prevDimension = dim
            } else {
                prevDimension = ""
            }

            val armor = player.armorValue
            if (prevArmor >= 0 && armor != prevArmor) fireIfBound("onArmourChange")
            prevArmor = armor

            // Expensive: armor-slot scan — only when a macro listens.
            if (engine.macros.hasEvent("onArmourDurabilityChange")) {
                var armorDur = 0
                for (s in arrayOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
                    val st = player.getItemBySlot(s)
                    armorDur += (st.maxDamage - st.damageValue).coerceAtLeast(0)
                }
                if (prevArmorDur >= 0 && armorDur != prevArmorDur) engine.fireEvent("onArmourDurabilityChange", sink)
                prevArmorDur = armorDur
            } else {
                prevArmorDur = -1
            }

            val hm = player.mainHandItem
            val heldDur = (hm.maxDamage - hm.damageValue).coerceAtLeast(0)
            if (prevHeldDur >= 0 && heldDur != prevHeldDur) fireIfBound("onItemDurabilityChange")
            prevHeldDur = heldDur

            // Expensive: full inventory scan — only when a macro listens for pickups.
            if (engine.macros.hasEvent("onPickupItem")) {
                var invCount = 0
                for (i in 0 until player.inventory.containerSize) invCount += player.inventory.getItem(i).count
                if (prevInvCount in 0 until invCount) engine.fireEvent("onPickupItem", sink) // count rose = picked up
                prevInvCount = invCount
            } else {
                prevInvCount = -1
            }

            if (engine.macros.hasEvent("onPlayerJoined")) {
                val online = Minecraft.getInstance().connection?.onlinePlayers?.size ?: 0
                if (prevOnline in 0 until online) engine.fireEvent("onPlayerJoined", sink)
                prevOnline = online
            } else {
                prevOnline = -1
            }

            if (engine.macros.hasEvent("onModeChange")) {
                val mode = Minecraft.getInstance().gameMode?.playerMode?.name ?: ""
                if (prevGameMode.isNotEmpty() && mode != prevGameMode) engine.fireEvent("onModeChange", sink)
                prevGameMode = mode
            } else {
                prevGameMode = ""
            }
        } else {
            prevHealth = -1; prevHunger = -1; prevLevel = -1; prevHeldId = ""
            prevAir = -1; prevTotalXp = -1; prevSlot = -1; prevRaining = -1; prevDimension = ""
            prevArmor = -1; prevArmorDur = -1; prevHeldDur = -1; prevInvCount = -1; prevOnline = -1; prevGameMode = ""
        }
    }

    private fun fireIfBound(event: String) {
        if (engine.macros.hasEvent(event)) engine.fireEvent(event, sink)
    }
    //?}

    // Open the module-toggle GUI. The screen only exists on >=1.21; older = no-op. Two
    // independent gates (not if/else) so each multi-line region stays brace-balanced per version.
    //? if >=1.21 {
    private fun openModuleScreen() {
        Minecraft.getInstance().setScreen(dev.macromod.fabric.ui.ModuleScreen(modules))
    }
    //?}
    //? if <1.21 {
    /*private fun openModuleScreen() {}*/
    //?}

    // Open the REPL console. Like the module GUI it only exists on >=1.21; older = no-op.
    //? if >=1.21 {
    private fun openReplScreen() {
        Minecraft.getInstance().setScreen(dev.macromod.fabric.ui.ReplScreen { src -> runReplLine(src) })
    }

    /** Compile + run a typed REPL line against the live engine context; returns captured output. */
    private fun runReplLine(src: String): List<String> {
        val captured = ArrayList<String>()
        val capture = object : OutputSink {
            override fun chat(message: String) { captured.add(message) }
            override fun log(message: String) { captured.add(message) }
            override fun clearChat() { captured.clear() }
            override fun logRaw(json: String) { captured.add(json) }
            override fun logTo(target: String, text: String) { captured.add("$target: $text") }
            override fun selectChannel(channel: String) {}
        }
        val wrapped = if (src.contains("\$\${")) src else "\$\${ $src }\$\$"
        try {
            engine.host.run(
                wrapped, capture,
                registry = engine.variables,
                input = engine.input,
                navigator = engine.navigator,
                client = engine.client,
            )
        } catch (e: Exception) {
            captured.add("error: " + (e.message ?: e.toString()))
        }
        return if (captured.isEmpty()) listOf("(ran; no output)") else captured
    }
    //?}
    //? if <1.21 {
    /*private fun openReplScreen() {}*/
    //?}

    //? if >=1.19.3 {
    /** Fire `onChat` whenever the client receives a chat/game message (modern Fabric event). */
    private fun wireChatReceive() {
        ClientReceiveMessageEvents.GAME.register { message, _ ->
            if (engine.macros.hasEvent("onChat")) {
                setChatVars(message.string, null)
                engine.fireEvent("onChat", sink)
            }
        }
        ClientReceiveMessageEvents.CHAT.register { message, _, sender, _, _ ->
            if (engine.macros.hasEvent("onChat")) {
                setChatVars(message.string, sender?.name)
                engine.fireEvent("onChat", sink)
            }
        }
        // onFilterableChat — fire per received line; a bound macro may filter()/pass() to suppress it.
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ ->
            allowChat(message.string, null)
        }
        ClientReceiveMessageEvents.ALLOW_CHAT.register { message, _, sender, _, _ ->
            allowChat(message.string, sender?.name)
        }
        // onSendChatMessage — fires when the local player sends a chat line or command.
        ClientSendMessageEvents.CHAT.register { message ->
            if (engine.macros.hasEvent("onSendChatMessage")) {
                setChatVars(message, null)
                engine.fireEvent("onSendChatMessage", sink)
            }
        }
        ClientSendMessageEvents.COMMAND.register { message ->
            if (engine.macros.hasEvent("onSendChatMessage")) {
                setChatVars(message, null)
                engine.fireEvent("onSendChatMessage", sink)
            }
        }
    }

    /** Expose the chat line to macros as %CHAT% / %CHATCLEAN% (+ %CHATPLAYER% when known). */
    private fun setChatVars(message: String, player: String?) {
        engine.variables.setTransient("CHAT", dev.macromod.engine.value.Value.Str(message))
        engine.variables.setTransient("CHATCLEAN", dev.macromod.engine.value.Value.Str(message))
        engine.variables.setTransient("CHATPLAYER", dev.macromod.engine.value.Value.Str(player ?: ""))
        engine.variables.setTransient("CHATMESSAGE", dev.macromod.engine.value.Value.Str(message))
    }

    /**
     * onFilterableChat handler: fire the event with the chat vars set; a bound macro may call
     * filter() to suppress the line. Returns whether the line is allowed (default true, so with no
     * filtering macro bound nothing is ever hidden).
     */
    private fun allowChat(message: String, player: String?): Boolean {
        if (!engine.macros.hasEvent("onFilterableChat")) return true
        chatFilter.reset()
        setChatVars(message, player)
        engine.fireEvent("onFilterableChat", sink)
        return !chatFilter.suppressed
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
            val mc = Minecraft.getInstance()
            // No player yet (title / menu / connecting screen): still resolve the built-ins that
            // don't need one, instead of returning null for everything.
            val player = mc.player ?: return@addEnvProvider envWithoutPlayer(mc, name.uppercase())
            when (name.uppercase()) {
                // identity
                "PLAYER", "NAME" -> Value.Str(player.name.string)
                // vitals
                "HEALTH" -> Value.Num(player.health.toInt())
                "MAXHEALTH" -> Value.Num(player.maxHealth.toInt())
                "HUNGER" -> Value.Num(player.foodData.foodLevel)
                "SATURATION" -> Value.Num(player.foodData.saturationLevel.toInt())
                "OXYGEN" -> Value.Num(player.airSupply)
                "ARMOUR", "ARMOR" -> Value.Num(player.armorValue)
                // experience
                "LEVEL" -> Value.Num(player.experienceLevel)
                "TOTALXP" -> Value.Num(player.totalExperience)
                // position + facing (block-integer; yRot/xRot read on every >=1.16 target)
                "XPOS" -> Value.Num(player.x.toInt())
                "YPOS" -> Value.Num(player.y.toInt())
                "ZPOS" -> Value.Num(player.z.toInt())
                "YAW" -> Value.Num(player.yRot.toInt())
                "PITCH" -> Value.Num(player.xRot.toInt())
                // abilities
                "FLYING" -> Value.Bool(player.abilities.flying)
                "CANFLY" -> Value.Bool(player.abilities.mayfly)
                // held item (main hand)
                "HELDITEMNAME" -> Value.Str(player.mainHandItem.hoverName.string)
                "HELDITEMCOUNT" -> Value.Num(player.mainHandItem.count)
                // position (3-decimal string form)
                "XPOSF" -> Value.Str("%.3f".format(player.x))
                "YPOSF" -> Value.Str("%.3f".format(player.y))
                "ZPOSF" -> Value.Str("%.3f".format(player.z))
                // player state flags
                "SHIFT", "SNEAKING" -> Value.Bool(player.isShiftKeyDown)
                "SPRINTING" -> Value.Bool(player.isSprinting)
                "ONFIRE" -> Value.Bool(player.isOnFire)
                "SWIMMING" -> Value.Bool(player.isSwimming)
                // world
                "TIME" -> Value.Num(((mc.level?.dayTime ?: 0L) % 24000L).toInt())
                "RAINING" -> Value.Bool(mc.level?.isRaining ?: false)
                // ResourceKey's accessor was renamed location() -> identifier() at 1.21.11
                // (along with ResourceLocation -> Identifier). Source-of-truth 1.21.1 (<1.21.11).
                //? if >=1.21.11 {
                /*"DIMENSION" -> Value.Str(mc.level?.dimension()?.identifier()?.toString() ?: "")*/
                //?}
                //? if <1.21.11 {
                "DIMENSION" -> Value.Str(mc.level?.dimension()?.location()?.toString() ?: "")
                //?}
                "DIFFICULTY" -> Value.Str(mc.level?.difficulty?.name ?: "")
                // held item (extended): registry id + durability
                "HELDITEMID" -> Value.Str(itemRegistryId(player.mainHandItem))
                "HELDITEMDAMAGE" -> Value.Num(player.mainHandItem.damageValue)
                "HELDITEMMAXDAMAGE" -> Value.Num(player.mainHandItem.maxDamage)
                "HELDITEMDURABILITY" -> Value.Num((player.mainHandItem.maxDamage - player.mainHandItem.damageValue).coerceAtLeast(0))
                // off-hand item
                "OFFHANDNAME" -> Value.Str(player.offhandItem.hoverName.string)
                "OFFHANDCOUNT" -> Value.Num(player.offhandItem.count)
                "OFFHANDID" -> Value.Str(itemRegistryId(player.offhandItem))
                // selected hotbar slot (0-8); accessor privatised at 1.21.5
                "SLOT", "HOTBARSLOT" -> Value.Num(selectedSlot(player))
                // block-integer position
                "BLOCKX" -> Value.Num(player.blockPosition().x)
                "BLOCKY" -> Value.Num(player.blockPosition().y)
                "BLOCKZ" -> Value.Num(player.blockPosition().z)
                // physical state
                "FALLDISTANCE" -> Value.Num(player.fallDistance.toInt())
                "EYEHEIGHT" -> Value.Str("%.2f".format(player.eyeHeight))
                "INWATER" -> Value.Bool(player.isInWater)
                "INLAVA" -> Value.Bool(player.isInLava)
                "MAXAIR" -> Value.Num(player.maxAirSupply)
                // light level at the player's block (0-15; getMaxLocalRawBrightness on LevelReader)
                "LIGHT" -> Value.Num(mc.level?.getMaxLocalRawBrightness(player.blockPosition()) ?: 0)
                // total world age in ticks (truncated into Int range)
                "GAMETIME" -> Value.Num(((mc.level?.gameTime ?: 0L) % Int.MAX_VALUE).toInt())
                // MKB-named aliases for the held item (ddoerr names: ITEM/ITEMNAME/DURABILITY/STACKSIZE)
                "ITEM" -> Value.Str(itemRegistryId(player.mainHandItem))
                "ITEMNAME" -> Value.Str(player.mainHandItem.hoverName.string)
                "ITEMDAMAGE" -> Value.Num(player.mainHandItem.maxDamage)
                "DURABILITY" -> Value.Num((player.mainHandItem.maxDamage - player.mainHandItem.damageValue).coerceAtLeast(0))
                "STACKSIZE" -> Value.Num(player.mainHandItem.count)
                // MKB-named off-hand aliases
                "OFFHANDITEM" -> Value.Str(itemRegistryId(player.offhandItem))
                "OFFHANDITEMNAME" -> Value.Str(player.offhandItem.hoverName.string)
                "OFFHANDSTACKSIZE" -> Value.Num(player.offhandItem.count)
                "OFFHANDDURABILITY" -> Value.Num((player.offhandItem.maxDamage - player.offhandItem.damageValue).coerceAtLeast(0))
                // window / server / GUI / world-day (MKB names)
                "DISPLAYWIDTH" -> Value.Num(mc.window.guiScaledWidth)
                "DISPLAYHEIGHT" -> Value.Num(mc.window.guiScaledHeight)
                "SERVER" -> Value.Str(mc.currentServer?.ip ?: "")
                "GUI" -> Value.Str(mc.screen?.javaClass?.simpleName ?: "")
                "DAY" -> Value.Num(((mc.level?.dayTime ?: 0L) / 24000L).toInt())
                "CARDINALYAW" -> Value.Num(((player.yRot.toInt() % 360) + 540) % 360)
                // settings: video options (OptionInstance getters @1.19; plain fields below)
                "FOV" -> Value.Num(optFov(mc.options))
                "GAMMA" -> Value.Str("%.2f".format(optGamma(mc.options)))
                "SENSITIVITY" -> Value.Str("%.2f".format(optSensitivity(mc.options)))
                "CAMERA" -> Value.Str(mc.options.cameraType.name)
                // settings: sound volumes 0-100 (getSoundSourceVolume is stable across versions)
                "SOUND" -> Value.Num(volume(mc, net.minecraft.sounds.SoundSource.MASTER))
                "MUSIC" -> Value.Num(volume(mc, net.minecraft.sounds.SoundSource.MUSIC))
                "AMBIENTVOLUME" -> Value.Num(volume(mc, net.minecraft.sounds.SoundSource.AMBIENT))
                "BLOCKVOLUME" -> Value.Num(volume(mc, net.minecraft.sounds.SoundSource.BLOCKS))
                "HOSTILEVOLUME" -> Value.Num(volume(mc, net.minecraft.sounds.SoundSource.HOSTILE))
                "NEUTRALVOLUME" -> Value.Num(volume(mc, net.minecraft.sounds.SoundSource.NEUTRAL))
                "PLAYERVOLUME" -> Value.Num(volume(mc, net.minecraft.sounds.SoundSource.PLAYERS))
                "RECORDVOLUME" -> Value.Num(volume(mc, net.minecraft.sounds.SoundSource.RECORDS))
                "WEATHERVOLUME" -> Value.Num(volume(mc, net.minecraft.sounds.SoundSource.WEATHER))
                // world (extended): biome (Holder @1.18.2, ResourceKey rename @1.21.11), times, rain
                "BIOME" -> Value.Str(biomeName(mc))
                "TICKS" -> Value.Num(((mc.level?.dayTime ?: 0L) % Int.MAX_VALUE).toInt())
                "TOTALTICKS" -> Value.Num(((mc.level?.gameTime ?: 0L) % Int.MAX_VALUE).toInt())
                "DAYTIME" -> Value.Str(dayTimeString(mc))
                "RAIN" -> Value.Str("%.2f".format(mc.level?.getRainLevel(1.0f) ?: 0.0f))
                // world seed is server-authoritative and not exposed to the client (n/a on MP)
                "SEED" -> Value.Str("")
                // looking-at (mc.hitResult): type / id / name / block pos / face
                "HIT" -> Value.Str(hitType(mc))
                "HITID" -> Value.Str(hitId(mc))
                "HITNAME" -> Value.Str(hitName(mc))
                "HITX" -> Value.Num(hitBlockPos(mc)?.x ?: 0)
                "HITY" -> Value.Num(hitBlockPos(mc)?.y ?: 0)
                "HITZ" -> Value.Num(hitBlockPos(mc)?.z ?: 0)
                "HITSIDE" -> Value.Str(hitSide(mc))
                "HITUUID" -> Value.Str(hitUuid(mc))
                // equipped armor (getItemBySlot): HELM/CHESTPLATE/LEGGINGS/BOOTS x ID/NAME/DAMAGE/DURABILITY
                "HELMID" -> Value.Str(itemRegistryId(player.getItemBySlot(EquipmentSlot.HEAD)))
                "HELMNAME" -> Value.Str(player.getItemBySlot(EquipmentSlot.HEAD).hoverName.string)
                "HELMDAMAGE" -> Value.Num(player.getItemBySlot(EquipmentSlot.HEAD).maxDamage)
                "HELMDURABILITY" -> Value.Num(durability(player.getItemBySlot(EquipmentSlot.HEAD)))
                "CHESTPLATEID" -> Value.Str(itemRegistryId(player.getItemBySlot(EquipmentSlot.CHEST)))
                "CHESTPLATENAME" -> Value.Str(player.getItemBySlot(EquipmentSlot.CHEST).hoverName.string)
                "CHESTPLATEDAMAGE" -> Value.Num(player.getItemBySlot(EquipmentSlot.CHEST).maxDamage)
                "CHESTPLATEDURABILITY" -> Value.Num(durability(player.getItemBySlot(EquipmentSlot.CHEST)))
                "LEGGINGSID" -> Value.Str(itemRegistryId(player.getItemBySlot(EquipmentSlot.LEGS)))
                "LEGGINGSNAME" -> Value.Str(player.getItemBySlot(EquipmentSlot.LEGS).hoverName.string)
                "LEGGINGSDAMAGE" -> Value.Num(player.getItemBySlot(EquipmentSlot.LEGS).maxDamage)
                "LEGGINGSDURABILITY" -> Value.Num(durability(player.getItemBySlot(EquipmentSlot.LEGS)))
                "BOOTSID" -> Value.Str(itemRegistryId(player.getItemBySlot(EquipmentSlot.FEET)))
                "BOOTSNAME" -> Value.Str(player.getItemBySlot(EquipmentSlot.FEET).hoverName.string)
                "BOOTSDAMAGE" -> Value.Num(player.getItemBySlot(EquipmentSlot.FEET).maxDamage)
                "BOOTSDURABILITY" -> Value.Num(durability(player.getItemBySlot(EquipmentSlot.FEET)))
                // identity / session / time / item-id-damage (cheap client reads)
                "DISPLAYNAME" -> Value.Str(player.displayName?.string ?: player.name.string)
                "UUID" -> Value.Str(player.stringUUID)
                "GAMEMODE" -> Value.Str(mc.gameMode?.playerMode?.name ?: "")
                "MODE" -> Value.Num(mc.gameMode?.playerMode?.ordinal ?: 0)
                "DIRECTION" -> Value.Str(directionOf(player.yRot))
                "CONFIG" -> Value.Str(engine.configs.active.name)
                "VEHICLE" -> Value.Str(player.vehicle?.let { entityTypeId(it) } ?: "")
                "VEHICLEHEALTH" -> Value.Num((player.vehicle as? net.minecraft.world.entity.LivingEntity)?.health?.toInt() ?: 0)
                "ONLINEPLAYERS" -> Value.Num(mc.connection?.onlinePlayers?.size ?: 0)
                "SERVERNAME" -> Value.Str(mc.currentServer?.name ?: "")
                "INVSLOT" -> Value.Num(selectedSlot(player))
                "CONTAINERSLOTS" -> Value.Num(player.containerMenu.slots.size)
                "DAYTICKS" -> Value.Num(((((mc.level?.dayTime ?: 0L) % 24000L) - 6000L + 24000L) % 24000L).toInt())
                "TIMESTAMP" -> Value.Str((System.currentTimeMillis() / 1000L).toString())
                "DATE" -> Value.Str(java.time.LocalDate.now().toString())
                "DATETIME" -> Value.Str(java.time.LocalDateTime.now().toString())
                "UNIQUEID" -> Value.Str(java.util.UUID.randomUUID().toString())
                "ITEMIDDMG" -> Value.Str(itemRegistryId(player.mainHandItem) + ":" + player.mainHandItem.damageValue)
                "OFFHANDITEMIDDMG" -> Value.Str(itemRegistryId(player.offhandItem) + ":" + player.offhandItem.damageValue)
                "OFFHANDITEMDAMAGE" -> Value.Num(player.offhandItem.maxDamage)
                // item "internal code" = the registry id (post-1.13 the stable identifier)
                "ITEMCODE" -> Value.Str(itemRegistryId(player.mainHandItem))
                "OFFHANDITEMCODE" -> Value.Str(itemRegistryId(player.offhandItem))
                // input states (live, via GLFW): modifiers, mouse buttons, and %KEY_<name>%
                "CTRL" -> Value.Bool(keyDown(mc, GLFW.GLFW_KEY_LEFT_CONTROL) || keyDown(mc, GLFW.GLFW_KEY_RIGHT_CONTROL))
                "ALT" -> Value.Bool(keyDown(mc, GLFW.GLFW_KEY_LEFT_ALT) || keyDown(mc, GLFW.GLFW_KEY_RIGHT_ALT))
                "LMOUSE" -> Value.Bool(mouseDown(mc, GLFW.GLFW_MOUSE_BUTTON_LEFT))
                "RMOUSE" -> Value.Bool(mouseDown(mc, GLFW.GLFW_MOUSE_BUTTON_RIGHT))
                "MIDDLEMOUSE" -> Value.Bool(mouseDown(mc, GLFW.GLFW_MOUSE_BUTTON_MIDDLE))
                else -> if (name.uppercase().startsWith("KEY_")) Value.Bool(isNamedKeyDown(mc, name.substring(4))) else null
            }
        }
    }

    /**
     * Register host iterators for `foreach(<var>, <iterator>)`: the online players, the hotbar item
     * ids (slots 0-8), and the full inventory item ids. Effects / enchantments stay deferred (their
     * registry access is Holder-wrapped and churns hard across versions).
     */
    private fun registerIterators() {
        engine.variables.addIteratorProvider { name ->
            val mc = Minecraft.getInstance()
            when (name) {
                "players" -> mc.level?.players()?.map { Value.Str(it.name.string) }
                "hotbar" -> mc.player?.let { p -> (0..8).map { Value.Str(itemRegistryId(p.inventory.getItem(it))) } }
                "inventory" -> mc.player?.let { p -> (0 until p.inventory.containerSize).map { Value.Str(itemRegistryId(p.inventory.getItem(it))) } }
                // scoreboard iterators: team names + objective names (the scoreboard API is stable)
                "teams" -> mc.level?.scoreboard?.playerTeams?.map { Value.Str(it.name) }
                "objectives" -> mc.level?.scoreboard?.objectives?.map { Value.Str(it.name) }
                else -> null
            }
        }
    }

    // Sound-source volume as 0-100; getSoundSourceVolume is stable across 1.16->1.21.
    private fun volume(mc: Minecraft, source: net.minecraft.sounds.SoundSource): Int =
        (mc.options.getSoundSourceVolume(source) * 100).toInt()

    // Current biome id. getBiome returns a Holder<Biome> from 1.18.2 (plain Biome before, with no
    // clean client-side name -> "" there); the ResourceKey accessor was renamed location()->identifier()
    // at 1.21.11. Nested gates pick the right path per version.
    private fun biomeName(mc: Minecraft): String {
        val level = mc.level ?: return ""
        val player = mc.player ?: return ""
        //? if >=1.18.2 {
        val holder = level.getBiome(player.blockPosition())
        //? if >=1.21.11 {
        /*return holder.unwrapKey().map { it.identifier().toString() }.orElse("")*/
        //?}
        //? if <1.21.11 {
        return holder.unwrapKey().map { it.location().toString() }.orElse("")
        //?}
        //?}
        //? if <1.18.2 {
        /*return ""*/
        //?}
    }

    // In-game time as hh:mm (MC dawn = dayTime 0 = 06:00).
    private fun dayTimeString(mc: Minecraft): String {
        val t = (((mc.level?.dayTime ?: 0L) % 24000L) + 24000L) % 24000L
        val hours = ((t / 1000L + 6L) % 24L).toInt()
        val mins = ((t % 1000L) * 60L / 1000L).toInt()
        return "%02d:%02d".format(hours, mins)
    }

    // Looking-at (mc.hitResult) helpers ------------------------------------------------------------
    private fun hitType(mc: Minecraft): String = when (mc.hitResult?.type) {
        net.minecraft.world.phys.HitResult.Type.BLOCK -> "block"
        net.minecraft.world.phys.HitResult.Type.ENTITY -> "entity"
        else -> "miss"
    }

    private fun hitBlockPos(mc: Minecraft): net.minecraft.core.BlockPos? {
        val hit = mc.hitResult
        return if (hit is net.minecraft.world.phys.BlockHitResult) hit.blockPos else null
    }

    private fun hitId(mc: Minecraft): String {
        val hit = mc.hitResult ?: return ""
        if (hit is net.minecraft.world.phys.BlockHitResult) {
            val block = mc.level?.getBlockState(hit.blockPos)?.block ?: return ""
            return blockRegistryId(block)
        }
        if (hit is net.minecraft.world.phys.EntityHitResult) return entityTypeId(hit.entity)
        return ""
    }

    private fun hitName(mc: Minecraft): String {
        val hit = mc.hitResult ?: return ""
        if (hit is net.minecraft.world.phys.BlockHitResult) {
            val state = mc.level?.getBlockState(hit.blockPos) ?: return ""
            return state.block.name.string
        }
        if (hit is net.minecraft.world.phys.EntityHitResult) return hit.entity.name.string
        return ""
    }

    private fun hitUuid(mc: Minecraft): String {
        val hit = mc.hitResult
        return if (hit is net.minecraft.world.phys.EntityHitResult) hit.entity.stringUUID else ""
    }

    private fun hitSide(mc: Minecraft): String {
        val hit = mc.hitResult
        if (hit !is net.minecraft.world.phys.BlockHitResult) return ""
        return when (hit.direction) {
            net.minecraft.core.Direction.DOWN -> "B"
            net.minecraft.core.Direction.UP -> "T"
            net.minecraft.core.Direction.NORTH -> "N"
            net.minecraft.core.Direction.SOUTH -> "S"
            net.minecraft.core.Direction.WEST -> "W"
            net.minecraft.core.Direction.EAST -> "E"
            else -> ""
        }
    }

    private fun blockRegistryId(block: net.minecraft.world.level.block.Block): String {
        //? if >=1.19.3 {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString()
        //?}
        //? if <1.19.3 {
        /*return net.minecraft.core.Registry.BLOCK.getKey(block).toString()*/
        //?}
    }

    private fun entityTypeId(entity: net.minecraft.world.entity.Entity): String {
        //? if >=1.19.3 {
        return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString()
        //?}
        //? if <1.19.3 {
        /*return net.minecraft.core.Registry.ENTITY_TYPE.getKey(entity.type).toString()*/
        //?}
    }

    // Remaining uses (durability) of an item stack: 0 for non-damageable / empty.
    private fun durability(stack: net.minecraft.world.item.ItemStack): Int =
        (stack.maxDamage - stack.damageValue).coerceAtLeast(0)

    // Facing direction first char (MC yaw: 0=S, 90=W, 180=N, 270=E).
    private fun directionOf(yaw: Float): String {
        val y = ((yaw % 360f) + 360f) % 360f
        return when (((y + 45f) / 90f).toInt() % 4) { 0 -> "S"; 1 -> "W"; 2 -> "N"; else -> "E" }
    }

    // The GLFW window handle. Window's accessor was renamed getWindow() -> handle() at 1.21.9.
    private fun windowHandle(mc: Minecraft): Long {
        //? if >=1.21.9 {
        /*return mc.window.handle()*/
        //?}
        //? if <1.21.9 {
        return mc.window.window
        //?}
    }

    // Input-state helpers (GLFW directly, avoiding the InputConstants.isKeyDown long->Window churn).
    private fun keyDown(mc: Minecraft, keyCode: Int): Boolean =
        GLFW.glfwGetKey(windowHandle(mc), keyCode) == GLFW.GLFW_PRESS

    private fun mouseDown(mc: Minecraft, button: Int): Boolean =
        GLFW.glfwGetMouseButton(windowHandle(mc), button) == GLFW.GLFW_PRESS

    // %KEY_<name>% (LWJGL names, e.g. KEY_W -> key.keyboard.w); unknown names read as not-pressed.
    private fun isNamedKeyDown(mc: Minecraft, keyName: String): Boolean = try {
        val key = com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard." + keyName.lowercase())
        keyDown(mc, key.value)
    } catch (e: Exception) {
        false
    }

    // Video options. OptionInstance accessors arrived at 1.19; below that they are plain fields.
    private fun optFov(o: net.minecraft.client.Options): Int {
        //? if >=1.19 {
        return o.fov().get()
        //?}
        //? if <1.19 {
        /*return o.fov.toInt()*/
        //?}
    }

    private fun optGamma(o: net.minecraft.client.Options): Double {
        //? if >=1.19 {
        return o.gamma().get()
        //?}
        //? if <1.19 {
        /*return o.gamma*/
        //?}
    }

    private fun optSensitivity(o: net.minecraft.client.Options): Double {
        //? if >=1.19 {
        return o.sensitivity().get()
        //?}
        //? if <1.19 {
        /*return o.sensitivity*/
        //?}
    }

    // Built-ins that need no player, so they resolve on the title / menu / connecting screen too.
    private fun envWithoutPlayer(mc: Minecraft, name: String): Value? = when (name) {
        "FOV" -> Value.Num(optFov(mc.options))
        "GAMMA" -> Value.Str("%.2f".format(optGamma(mc.options)))
        "SENSITIVITY" -> Value.Str("%.2f".format(optSensitivity(mc.options)))
        "CAMERA" -> Value.Str(mc.options.cameraType.name)
        "SOUND" -> Value.Num(volume(mc, net.minecraft.sounds.SoundSource.MASTER))
        "MUSIC" -> Value.Num(volume(mc, net.minecraft.sounds.SoundSource.MUSIC))
        "AMBIENTVOLUME" -> Value.Num(volume(mc, net.minecraft.sounds.SoundSource.AMBIENT))
        "BLOCKVOLUME" -> Value.Num(volume(mc, net.minecraft.sounds.SoundSource.BLOCKS))
        "HOSTILEVOLUME" -> Value.Num(volume(mc, net.minecraft.sounds.SoundSource.HOSTILE))
        "NEUTRALVOLUME" -> Value.Num(volume(mc, net.minecraft.sounds.SoundSource.NEUTRAL))
        "PLAYERVOLUME" -> Value.Num(volume(mc, net.minecraft.sounds.SoundSource.PLAYERS))
        "RECORDVOLUME" -> Value.Num(volume(mc, net.minecraft.sounds.SoundSource.RECORDS))
        "WEATHERVOLUME" -> Value.Num(volume(mc, net.minecraft.sounds.SoundSource.WEATHER))
        "DISPLAYWIDTH" -> Value.Num(mc.window.guiScaledWidth)
        "DISPLAYHEIGHT" -> Value.Num(mc.window.guiScaledHeight)
        "GUI" -> Value.Str(mc.screen?.javaClass?.simpleName ?: "")
        "SERVER" -> Value.Str(mc.currentServer?.ip ?: "")
        "SERVERNAME" -> Value.Str(mc.currentServer?.name ?: "")
        "DATE" -> Value.Str(java.time.LocalDate.now().toString())
        "DATETIME" -> Value.Str(java.time.LocalDateTime.now().toString())
        "TIMESTAMP" -> Value.Str((System.currentTimeMillis() / 1000L).toString())
        "UNIQUEID" -> Value.Str(java.util.UUID.randomUUID().toString())
        else -> null
    }

    // Registry id of an item stack (e.g. "minecraft:diamond_sword"); the item registry moved from
    // the static Registry.* to BuiltInRegistries at 1.19.3 (same cutover as FabricWorldQuery).
    private fun itemRegistryId(stack: net.minecraft.world.item.ItemStack): String {
        if (stack.isEmpty) return ""
        //? if >=1.19.3 {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item).toString()
        //?}
        //? if <1.19.3 {
        /*return net.minecraft.core.Registry.ITEM.getKey(stack.item).toString()*/
        //?}
    }

    // Selected hotbar slot (0-8); the `selected` field was privatised at 1.21.5 in favour of getSelectedSlot().
    private fun selectedSlot(player: net.minecraft.world.entity.player.Player): Int {
        //? if >=1.21.5 {
        /*return player.inventory.getSelectedSlot()*/
        //?}
        //? if <1.21.5 {
        return player.inventory.selected
        //?}
    }
    //?}
}
