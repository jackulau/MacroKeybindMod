package dev.macromod.fabric

import dev.macromod.engine.action.OutputSink
import dev.macromod.engine.action.builtin.Angle
import dev.macromod.engine.action.builtin.SettingScale
import dev.macromod.engine.macro.MacroBinding
import dev.macromod.engine.macro.MacroEngine
import dev.macromod.engine.macro.PlaybackMode
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
import dev.macromod.engine.variable.IteratorBundle
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

    /**
     * One reused [ModuleContext] whose [tick][ModuleContext.tick] is advanced each tick in
     * [tickOnce] — avoids allocating a fresh context every client tick (20/s) when only the
     * counter changes. Declared after [inputController]/[navigator] (a property delegate cannot
     * forward-reference later members). Built lazily on first tick, once the [engine]/[sink]
     * lateinits are set; the client tick is single-threaded, so [LazyThreadSafetyMode.NONE] is safe.
     */
    private val moduleCtx by lazy(LazyThreadSafetyMode.NONE) {
        ModuleContext(
            tick = 0L,
            input = inputController,
            output = sink,
            navigator = navigator,
            registry = engine.variables,
        )
    }

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
    private var prevArmorDurs = intArrayOf(-1, -1, -1, -1) // per-slot HEAD/CHEST/LEGS/FEET durability (-1 = empty); suppresses the armour-dur event on equip/unequip
    private var prevHeldDur = -1
    private var prevHeldDurId: String? = null // main-hand registry id (null = empty/first sample); suppresses onItemDurabilityChange on an item switch
    private var prevInv: Map<String, Int>? = null
    private var prevOnlineNames: Set<String>? = null
    // >0 suppresses onPickupItem/onPlayerJoined fires for a short window after a (re)join, so the
    // login packet burst (inventory + tab-list arriving a tick or two after the player spawns) is
    // absorbed into the baseline instead of mis-fired as a giant pickup / everyone-just-joined.
    // Mirrors MKB MacroEventDispatcherBuiltin's joinedGameDelay (set on connect, decremented per tick).
    private var joinSettleTicks = 0
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

        //? if >=1.16 {
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
        // KEYSTATE demo (goal 090): hold J to exercise the playback-mode state machine live — key-down on
        // press, key-held every repeatRateMs (1s) while held, key-up on release. Raw-poll driven by
        // tickKeys; the mc.screen==null guard stops it firing while a GUI/chat is open.
        engine.macros.add(
            MacroBinding(
                trigger = Trigger.Key(GLFW.GLFW_KEY_J),
                script = "\$\${ log(\"MacroKeybindMod: keystate down\") }\$\$",
                keyHeldScript = "\$\${ log(\"MacroKeybindMod: keystate held\") }\$\$",
                keyUpScript = "\$\${ log(\"MacroKeybindMod: keystate up\") }\$\$",
                mode = PlaybackMode.KEYSTATE,
                repeatRateMs = 1000,
                name = "demo-keystate",
            ),
        )
        // Mouse-button trigger demo (goal 091): MOUSE4 (the GLFW button-4 side button) fires a one-shot,
        // polled via glfwGetMouseButton through the same tickKeys drive as the key demos above.
        engine.macros.add(
            MacroBinding(
                trigger = Trigger.Mouse(GLFW.GLFW_MOUSE_BUTTON_4),
                script = "\$\${ log(\"MacroKeybindMod: mouse4!\") }\$\$",
                name = "demo-mouse",
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
            // Backstop: a throw from ANY per-tick work must never escape into Minecraft's tick loop
            // and hard-crash the client. Engine macro paths already route script errors to the HUD
            // (MacroEngine.start / tickWaits); this catches everything else — navigation, modules,
            // a goto script, key handling — logs it, and lets the client keep running.
            try {
                tickOnce()
            } catch (e: Throwable) {
                logger.warn("client tick handler threw; suppressed to keep the client alive", e)
            }
        }
    }

    /** The actual per-tick work, wrapped by [wireTick] in a crash backstop. */
    private fun tickOnce() {
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
        // Advance the one reused context's tick instead of allocating a fresh one each tick.
        moduleCtx.tick = moduleTick++
        modules.tick(moduleCtx)

        // Drive input-bound macros: poll each bound trigger's raw GLFW state and advance its playback-mode
        // state machine (ONESHOT one-shot on press, KEYSTATE key-down/held/up, CONDITIONAL branch).
        // Skipped when nothing is input-bound (idle-cheap) or a screen is open (don't fire while typing in
        // a GUI/chat). demoKey stays registered for the controls screen; firing is via this poll, so just
        // drain its click queue. setTriggerVars exposes %KEYID%/%KEYNAME% (>=1.16, this block's gate).
        demoKey?.let { while (it.consumeClick()) Unit }
        val mc = Minecraft.getInstance()
        if (mc.screen == null && engine.macros.hasInputBindings()) {
            // Route each binding's poll by its trigger kind: keyboard keys via glfwGetKey, mouse buttons via
            // glfwGetMouseButton (the same trusted primitive behind %LMOUSE%). Keyboard and mouse GLFW codes
            // overlap, so the Trigger subtype -- not the raw int -- decides which device to poll.
            engine.tickKeys(System.currentTimeMillis(), sink, onFire = ::setTriggerVars) { trigger ->
                when (trigger) {
                    is Trigger.Key -> keyDown(mc, trigger.keyCode)
                    is Trigger.Mouse -> mouseDown(mc, trigger.button)
                    else -> false
                }
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
                joinSettleTicks = 40 // ~2s: far longer than the sub-second login burst, far shorter than MKB's 10s
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
                if (screen.isNotEmpty()) {
                    engine.variables.setTransient("GUI", dev.macromod.engine.value.Value.Str(screen))
                    engine.fireEvent("onShowGui", sink)
                }
                prevScreen = screen
            }
        } else {
            prevScreen = ""
        }
        if (player != null) {
            if (joinSettleTicks > 0) joinSettleTicks-- // count down the post-join settle window
            val dead = !player.isAlive
            if (dead && !wasDead) fireIfBound("onDeath")
            wasDead = dead

            val health = Math.round(player.health) // round like %HEALTH% so onHealthChange OLD/NEW stay consistent
            if (prevHealth >= 0 && health != prevHealth) {
                fireChange("onHealthChange", "HEALTH", prevHealth, health)
                if (health < prevHealth) fireIfBound("onDamage")
            }
            prevHealth = health

            val hunger = player.foodData.foodLevel
            if (prevHunger >= 0 && hunger != prevHunger) fireChange("onFoodChange", "HUNGER", prevHunger, hunger)
            prevHunger = hunger

            val level = player.experienceLevel
            if (prevLevel >= 0 && level != prevLevel) fireChange("onLevelChange", "LEVEL", prevLevel, level)
            prevLevel = level

            val totalXp = player.totalExperience
            if (prevTotalXp >= 0 && totalXp != prevTotalXp) fireChange("onXPChange", "TOTALXP", prevTotalXp, totalXp)
            prevTotalXp = totalXp

            val air = player.airSupply
            if (prevAir >= 0 && air != prevAir) fireChange("onOxygenChange", "OXYGEN", prevAir, air)
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
            // emit 1-based slots so the event OLDINVSLOT/INVSLOT match a normal %INVSLOT% read
            if (prevSlot >= 0 && slot != prevSlot) fireChange("onInventorySlotChange", "INVSLOT", prevSlot + 1, slot + 1)
            prevSlot = slot

            val currentLevel = Minecraft.getInstance().level
            val raining = if (currentLevel?.isRaining == true) 1 else 0
            if (prevRaining >= 0 && raining != prevRaining) fireChange("onWeatherChange", "RAIN", prevRaining, raining)
            prevRaining = raining

            if (engine.macros.hasEvent("onWorldChange")) {
                val dim = currentLevel?.dimension()?.toString() ?: ""
                if (prevDimension.isNotEmpty() && dim != prevDimension) engine.fireEvent("onWorldChange", sink)
                prevDimension = dim
            } else {
                prevDimension = ""
            }

            val armor = player.armorValue
            if (prevArmor >= 0 && armor != prevArmor) fireChange("onArmourChange", "ARMOUR", prevArmor, armor)
            prevArmor = armor

            // Expensive: armor-slot scan — only when a macro listens.
            if (engine.macros.hasEvent("onArmourDurabilityChange")) {
                // Per-slot durability snapshot (-1 = empty) so equip/unequip (an empty-state flip) is
                // suppressed and only genuine wear/swap of a worn piece fires -- mirrors MKB's per-slot
                // suppressNext-on-empty-flip rather than firing on any change to a summed total.
                val slots = arrayOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
                val armorDurs = IntArray(slots.size) { i ->
                    val st = player.getItemBySlot(slots[i])
                    if (st.isEmpty) -1 else (st.maxDamage - st.damageValue).coerceAtLeast(0)
                }
                if (dev.macromod.engine.event.DurabilityWatch.armourDurabilityChanged(prevArmorDurs, armorDurs)) engine.fireEvent("onArmourDurabilityChange", sink)
                prevArmorDurs = armorDurs
            } else {
                prevArmorDurs = intArrayOf(-1, -1, -1, -1)
            }

            // Gate on item identity: a durability event means WEAR, not a swap. Switching the held
            // item re-baselines silently instead of firing (MKB isSameItem gate). Guarded by hasEvent
            // so the id lookup is skipped when nothing listens.
            if (engine.macros.hasEvent("onItemDurabilityChange")) {
                val hm = player.mainHandItem
                val heldDur = (hm.maxDamage - hm.damageValue).coerceAtLeast(0)
                val heldDurId = if (hm.isEmpty) null else itemRegistryId(hm)
                if (dev.macromod.engine.event.DurabilityWatch.heldDurabilityChanged(prevHeldDurId, heldDurId ?: "", prevHeldDur, heldDur)) engine.fireEvent("onItemDurabilityChange", sink)
                prevHeldDurId = heldDurId
                prevHeldDur = heldDur
            } else {
                prevHeldDurId = null
                prevHeldDur = -1
            }

            // Expensive: full inventory scan — only when a macro listens for pickups.
            if (engine.macros.hasEvent("onPickupItem")) {
                val inv = player.inventory
                val counts = HashMap<String, Int>()
                for (i in 0 until inv.containerSize) {
                    val st = inv.getItem(i)
                    if (!st.isEmpty) { val id = itemRegistryId(st); counts[id] = (counts[id] ?: 0) + st.count }
                }
                val prev = prevInv
                val delta = if (prev != null) dev.macromod.engine.event.EventPayloads.pickupDelta(prev, counts) else null
                if (delta != null && joinSettleTicks <= 0) {
                    val (id, amount) = delta
                    // PICKUPITEM name + PICKUPDATA damage come from a slot currently holding the picked id
                    var name = id
                    var damage = 0
                    for (i in 0 until inv.containerSize) {
                        val st = inv.getItem(i)
                        if (!st.isEmpty && itemRegistryId(st) == id) { name = st.hoverName.string; damage = st.damageValue; break }
                    }
                    engine.variables.setTransient("PICKUPID", Value.Str(id))
                    engine.variables.setTransient("PICKUPITEM", Value.Str(name))
                    engine.variables.setTransient("PICKUPAMOUNT", Value.Num(amount))
                    engine.variables.setTransient("PICKUPDATA", Value.Num(damage))
                    engine.fireEvent("onPickupItem", sink)
                }
                prevInv = counts
            } else {
                prevInv = null
            }

            if (engine.macros.hasEvent("onPlayerJoined")) {
                val names = Minecraft.getInstance().connection?.onlinePlayers?.mapTo(HashSet()) { it.profile.name } ?: emptySet()
                val prev = prevOnlineNames
                if (prev != null && joinSettleTicks <= 0) {
                    val joiner = dev.macromod.engine.event.EventPayloads.newJoiner(prev, names)
                    if (joiner != null) {
                        engine.variables.setTransient("JOINEDPLAYER", Value.Str(joiner))
                        engine.fireEvent("onPlayerJoined", sink)
                    }
                }
                prevOnlineNames = names
            } else {
                prevOnlineNames = null
            }

            if (engine.macros.hasEvent("onModeChange")) {
                val mode = Minecraft.getInstance().gameMode?.playerMode?.name ?: ""
                if (prevGameMode.isNotEmpty() && mode != prevGameMode) fireChange("onModeChange", "GAMEMODE", prevGameMode, mode)
                prevGameMode = mode
            } else {
                prevGameMode = ""
            }
        } else {
            prevHealth = -1; prevHunger = -1; prevLevel = -1; prevHeldId = ""
            prevAir = -1; prevTotalXp = -1; prevSlot = -1; prevRaining = -1; prevDimension = ""
            prevArmor = -1; prevArmorDurs = intArrayOf(-1, -1, -1, -1); prevHeldDur = -1; prevHeldDurId = null; prevInv = null; prevOnlineNames = null; prevGameMode = ""
        }
    }

    private fun fireIfBound(event: String) {
        if (engine.macros.hasEvent(event)) engine.fireEvent(event, sink)
    }

    /**
     * Fire a "*Change" [event] only if a macro listens, first injecting the documented
     * %OLD<base>% / %NEW<base>% delta variables (the value-watcher contract, EVENTS.md) so a bound
     * macro can read what changed. The injection runs only when bound, so the unbound per-tick path
     * stays allocation-free.
     */
    private fun fireChange(event: String, base: String, old: Int, new: Int) {
        if (!engine.macros.hasEvent(event)) return
        engine.variables.setTransient("OLD$base", dev.macromod.engine.value.Value.Num(old))
        engine.variables.setTransient("NEW$base", dev.macromod.engine.value.Value.Num(new))
        engine.fireEvent(event, sink)
    }

    /** String-valued variant of [fireChange] (e.g. game-mode names). */
    private fun fireChange(event: String, base: String, old: String, new: String) {
        if (!engine.macros.hasEvent(event)) return
        engine.variables.setTransient("OLD$base", dev.macromod.engine.value.Value.Str(old))
        engine.variables.setTransient("NEW$base", dev.macromod.engine.value.Value.Str(new))
        engine.fireEvent(event, sink)
    }

    /** Expose the trigger key/button as %KEYID% / %KEYNAME% before an input-bound macro runs —
     *  [MacroEngine.tickKeys] invokes this via its onFire hook, once per firing trigger. KEYID is the GLFW
     *  code (our own id-space, not MKB's LWJGL reserved range); KEYNAME is the human name. */
    private fun setTriggerVars(trigger: Trigger) {
        when (trigger) {
            is Trigger.Key -> {
                engine.variables.setTransient("KEYID", Value.Num(trigger.keyCode))
                engine.variables.setTransient("KEYNAME", Value.Str(keyName(trigger.keyCode)))
            }
            is Trigger.Mouse -> {
                engine.variables.setTransient("KEYID", Value.Num(trigger.button))
                engine.variables.setTransient("KEYNAME", Value.Str(mouseName(trigger.button)))
            }
            else -> {}
        }
    }

    private fun keyName(keyCode: Int): String =
        InputConstants.Type.KEYSYM.getOrCreate(keyCode).name.removePrefix("key.keyboard.").uppercase()

    /** Human name for a GLFW mouse button, mirroring MKB's LMOUSE / RMOUSE / MIDDLEMOUSE / MOUSE4+ scheme. */
    private fun mouseName(button: Int): String = when (button) {
        GLFW.GLFW_MOUSE_BUTTON_LEFT -> "LMOUSE"
        GLFW.GLFW_MOUSE_BUTTON_RIGHT -> "RMOUSE"
        GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> "MIDDLEMOUSE"
        else -> "MOUSE${button + 1}"
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

    /**
     * Expose the chat line to macros as %CHAT% (raw) / %CHATCLEAN% (codes stripped) / %CHATPLAYER%
     * (the sender) / %CHATMESSAGE% (the message with the sender prefix removed) — the MKB
     * OnChatProvider contract. The sender prefers the client-signed [player] when the modern chat
     * API gives one (a real signed sender beats a regex guess); for unsigned GAME-channel /
     * plugin-formatted lines it falls back to parsing the sender out of the text, the way MKB's
     * guessPlayer does. CHATMESSAGE is always the prefix-stripped body, matching MKB.
     */
    private fun setChatVars(message: String, player: String?) {
        val clean = dev.macromod.engine.text.stripFormattingCodes(message)
        val parsed = dev.macromod.engine.text.parseChatSender(clean)
        engine.variables.setTransient("CHAT", dev.macromod.engine.value.Value.Str(message))
        engine.variables.setTransient("CHATCLEAN", dev.macromod.engine.value.Value.Str(clean))
        engine.variables.setTransient("CHATPLAYER", dev.macromod.engine.value.Value.Str(player?.takeIf { it.isNotEmpty() } ?: parsed.player))
        engine.variables.setTransient("CHATMESSAGE", dev.macromod.engine.value.Value.Str(parsed.message))
    }

    /**
     * onFilterableChat handler: fire the event with the chat vars set; a bound macro may call
     * filter() to suppress the line. Returns whether the line is allowed (default true, so with no
     * filtering macro bound nothing is ever hidden).
     */
    private fun allowChat(message: String, player: String?): Boolean {
        if (!engine.macros.hasEvent("onFilterableChat") || !chatFilter.enabled) return true
        chatFilter.reset()
        setChatVars(message, player)
        engine.fireEvent("onFilterableChat", sink)
        return !chatFilter.suppressed
    }
    //?}

    //? if >=1.16 {
    /**
     * Register a player [dev.macromod.engine.variable.EnvProvider] exposing live client-player
     * state. Names are matched raw (uppercase) so scripts read `%HEALTH%`, `%XPOS%`, etc.
     * Yaw/pitch use the `getYRot()`/`getXRot()` getters introduced in 1.17; on 1.16 the values
     * live in the `yRot`/`xRot` fields, so those reads are inner-gated below.
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
                // MKB rounds health: VariableProviderPlayer.java:128 stores rk.d(player.cd()) (rk.d = Math.round),
                // matching the rk.d ports already used for COOLDOWN/ATTACKPOWER/etc. toInt() truncated a half-heart away.
                "HEALTH" -> Value.Num(Math.round(player.health))
                "MAXHEALTH" -> Value.Num(player.maxHealth.toInt())
                "HUNGER" -> Value.Num(player.foodData.foodLevel)
                "SATURATION" -> Value.Num(player.foodData.saturationLevel.toInt())
                "OXYGEN" -> Value.Num(player.airSupply)
                "ARMOUR", "ARMOR" -> Value.Num(player.armorValue)
                // experience
                "LEVEL" -> Value.Num(player.experienceLevel)
                "TOTALXP" -> Value.Num(player.totalExperience)
                // XP points into the current level (MKB VariableProviderPlayer.java:133)
                "XP" -> Value.Num((player.experienceProgress * player.getXpNeededForNextLevel()).toInt())
                // position + facing (block-integer; yRot/xRot read on every >=1.16 target)
                // MKB floors entity coords to block coords: rk.c(player.p/q/r) (VariableProviderPlayer.java:53-55),
                // the same floor Minecraft (and our FabricNavigator.blockPosition()) use. toInt() truncates toward
                // zero -> off by one at negative X/Z and sub-zero Y (1.18+), and mis-targets getid(~)/getidrel.
                "XPOS" -> Value.Num(Math.floor(player.x).toInt())
                "YPOS" -> Value.Num(Math.floor(player.y).toInt())
                "ZPOS" -> Value.Num(Math.floor(player.z).toInt())
                // yaw/pitch normalised to [0,360) — MKB VariableProviderPlayer.java:60-74,144-146
                // (raw reads were an incomplete port; calcyawto + CARDINALYAW already normalise).
                "YAW" -> Value.Num(Angle.wrap(player.yRot.toInt()))
                "PITCH" -> Value.Num(Angle.wrap(player.xRot.toInt()))
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
                "SHIFT" -> Value.Bool(keyDown(mc, GLFW.GLFW_KEY_LEFT_SHIFT) || keyDown(mc, GLFW.GLFW_KEY_RIGHT_SHIFT))
                "SNEAKING" -> Value.Bool(player.isShiftKeyDown)
                "SPRINTING" -> Value.Bool(player.isSprinting)
                "ONFIRE" -> Value.Bool(player.isOnFire)
                "SWIMMING" -> Value.Bool(player.isSwimming)
                // world
                "TIME" -> Value.Str(java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")))
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
                "LOCALDIFFICULTY" -> Value.Num(localDifficulty(mc, player))
                // held item (extended): registry id + durability
                "HELDITEMID" -> Value.Str(itemRegistryId(player.mainHandItem))
                "HELDITEMDAMAGE" -> Value.Num(player.mainHandItem.damageValue)
                "HELDITEMMAXDAMAGE" -> Value.Num(player.mainHandItem.maxDamage)
                "HELDITEMDURABILITY" -> Value.Num((player.mainHandItem.maxDamage - player.mainHandItem.damageValue).coerceAtLeast(0))
                // combat + item-use (MKB Player provider): attack-cooldown recovery 0-100, item-use tick counter.
                // getAttackStrengthScale(0.0f) = current-tick charge: %ATTACKPOWER% reaches 100 exactly when the
                // attack is fully charged, not a tick early. 1.0f projects one tick ahead (ticker+1)/delay, so a
                // macro swinging at ==100 would fire a tick early on a not-yet-full attack (a weaker hit).
                "ATTACKPOWER" -> Value.Num(Math.round(player.getAttackStrengthScale(0.0f) * 100f))
                "ITEMUSETICKS" -> Value.Num(player.getTicksUsingItem())
                "COOLDOWN" -> Value.Num(cooldownPct(player, player.mainHandItem))
                "OFFHANDCOOLDOWN" -> Value.Num(cooldownPct(player, player.offhandItem))
                "ITEMUSEPCT" -> Value.Num(itemUsePct(player))
                "BOWCHARGE" -> Value.Num(bowCharge(player))
                "ATTACKSPEED" -> Value.Num(attackSpeed(player))
                // off-hand item
                "OFFHANDNAME" -> Value.Str(player.offhandItem.hoverName.string)
                "OFFHANDCOUNT" -> Value.Num(player.offhandItem.count)
                "OFFHANDID" -> Value.Str(itemRegistryId(player.offhandItem))
                // selected hotbar slot, 1-based (1-9) to match MKB INVSLOT; accessor privatised at 1.21.5
                "SLOT", "HOTBARSLOT" -> Value.Num(selectedSlot(player) + 1)
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
                "CARDINALYAW" -> Value.Num(Angle.wrap(player.yRot.toInt() - 180))
                // settings: video options (OptionInstance getters @1.19; plain fields below)
                "FOV" -> Value.Num(optFov(mc.options))
                "FPS" -> Value.Num(currentFps(mc)) // MKB VariableProviderSettings.java:54
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
                // MKB rounds: VariableProviderPlayer.java:153 stores rk.d(vehicleHealth) (Math.round).
                "VEHICLEHEALTH" -> Value.Num((player.vehicle as? net.minecraft.world.entity.LivingEntity)?.health?.let { Math.round(it) } ?: 0)
                "ONLINEPLAYERS" -> Value.Num(mc.connection?.onlinePlayers?.size ?: 0)
                "SERVERNAME" -> Value.Str(mc.currentServer?.name ?: "")
                "SERVERMOTD" -> Value.Str(mc.currentServer?.motd?.string ?: "")
                "INVSLOT" -> Value.Num(selectedSlot(player) + 1) // 1-based, MKB VariableProviderPlayer.java:132
                "CONTAINERSLOTS" -> Value.Num(player.containerMenu.slots.size)
                "DAYTICKS" -> Value.Num((((mc.level?.dayTime ?: 0L) + 6000L) % 24000L).toInt())
                "TIMESTAMP" -> Value.Str((System.currentTimeMillis() / 1000L).toString())
                "DATE" -> Value.Str(java.time.LocalDate.now().toString())
                "DATETIME" -> Value.Str(java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
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
                "hotbar" -> mc.player?.let { p -> (0..8).map { Value.Str(itemRegistryId(p.inventory.getItem(it))) } }
                "inventory" -> mc.player?.let { p -> (0 until p.inventory.containerSize).map { Value.Str(itemRegistryId(p.inventory.getItem(it))) } }
                // scoreboard iterators: team names + objective names (the scoreboard API is stable)
                "teams" -> mc.level?.scoreboard?.playerTeams?.map { Value.Str(it.name) }
                "objectives" -> mc.level?.scoreboard?.objectives?.map { Value.Str(it.name) }
                else -> null
            }
        }
        // `effects` is our first MULTI-VAR (bundle) iterator: each active potion effect exposes the
        // fixed-name vars EFFECTID/EFFECT/EFFECTNAME/EFFECTPOWER/EFFECTTIME, with the loop var bound to
        // the display name. Mirrors MKB's ScriptedIteratorEffects.
        engine.variables.addBundleProvider { name ->
            when (name) {
                "players" -> playersBundles()
                "effects" -> effectsBundles()
                "properties" -> propertiesBundles()
                "enchantments" -> enchantmentsBundles()
                else -> null
            }
        }
    }

    // Online players as bundle-iterator elements, ground-truthed against ScriptedIteratorPlayers: iterates
    // the connection's FULL online roster (getOnlinePlayers -- the player-info/tab list, every connected
    // player, NOT just the nearby loaded entities mc.level.players() returns) and exposes PLAYERNAME = the
    // GameProfile name (MKB add("PLAYERNAME", playerEntry.a().getName())). Loop var binds the same name.
    private fun playersBundles(): List<IteratorBundle> {
        val connection = Minecraft.getInstance().connection ?: return emptyList()
        return connection.onlinePlayers.map { info ->
            val name = info.profile.name
            IteratorBundle(Value.Str(name), linkedMapOf("PLAYERNAME" to Value.Str(name)))
        }
    }

    // MKB's amplifier -> roman-numeral suffix for EFFECTNAME. Index 4 is " VI" (MKB skips " V");
    // reproduced verbatim for parity. amplifier past the end -> no suffix (matches MKB's length guard).
    private val effectAmplifierSuffixes = arrayOf("", " II", " III", " IV", " VI")

    // Active potion effects as bundle-iterator elements, ground-truthed against ScriptedIteratorEffects:
    // EFFECTID = registry id, EFFECT = uppercase-no-space localized name, EFFECTNAME = name + amplifier
    // suffix, EFFECTPOWER = amplifier+1, EFFECTTIME = duration/20 (seconds). Loop var binds EFFECTNAME.
    private fun effectsBundles(): List<IteratorBundle> {
        val player = Minecraft.getInstance().player ?: return emptyList()
        return player.activeEffects.map { inst ->
            // getEffect() returns a Holder<MobEffect> from 1.20.5 (the raw MobEffect before).
            //? if >=1.20.5 {
            val effect = inst.effect.value()
            //?}
            //? if <1.20.5 {
            /*val effect = inst.effect*/
            //?}
            val potionName = net.minecraft.client.resources.language.I18n.get(effect.descriptionId)
            val amplifier = inst.amplifier
            // Registry moved from Registry to BuiltInRegistries at 1.19.3 (as in itemRegistryId).
            //? if >=1.19.3 {
            val effectId = net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.getId(effect)
            //?}
            //? if <1.19.3 {
            /*val effectId = net.minecraft.core.Registry.MOB_EFFECT.getId(effect)*/
            //?}
            val displayName = potionName + (effectAmplifierSuffixes.getOrNull(amplifier) ?: "")
            IteratorBundle(Value.Str(displayName), linkedMapOf(
                "EFFECTID" to Value.Num(effectId),
                "EFFECT" to Value.Str(potionName.uppercase().replace(" ", "")),
                "EFFECTNAME" to Value.Str(displayName),
                "EFFECTPOWER" to Value.Num(amplifier + 1),
                "EFFECTTIME" to Value.Num(inst.duration / 20),
            ))
        }
    }

    // Block-state properties of the block in the crosshair as bundle-iterator elements, mirroring MKB's
    // ScriptedIteratorProperties: PROPNAME (property name, also the loop var) + PROPVALUE (stringified
    // value). MKB's getActualState is pre-1.13 only (block metadata merged into blockState since 1.13),
    // so the modern blockState is already the resolved state. Reuses the HIT* looking-at helpers.
    private fun propertiesBundles(): List<IteratorBundle> {
        val mc = Minecraft.getInstance()
        val pos = hitBlockPos(mc) ?: return emptyList()
        val state = mc.level?.getBlockState(pos) ?: return emptyList()
        return state.values.entries.map { (prop, value) ->
            val name = prop.name
            IteratorBundle(Value.Str(name), linkedMapOf(
                "PROPNAME" to Value.Str(name),
                "PROPVALUE" to Value.Str(value.toString().lowercase()),
            ))
        }
    }

    // Held-item enchantments as bundle-iterator elements, mirroring MKB's ScriptedIteratorEnchantments:
    // ENCHANTMENT = full localized name with level (e.g. "Sharpness V", also the loop var), ENCHANTMENTNAME
    // = base localized name (e.g. "Sharpness"), ENCHANTMENTPOWER = level. Reads the main-hand item plus
    // (on the component eras) its STORED_ENCHANTMENTS so a held enchanted book still enumerates. The
    // enchantment read path is a THREE-era cross-version split, ground-truthed via javap on the mapped jars:
    //   <1.20.5      : EnchantmentHelper.getEnchantments(stack) -> Map<Enchantment,Int>; instance
    //                  Enchantment.getFullname(level) + I18n(getDescriptionId()).
    //   1.20.5..<1.21: ItemEnchantments component (ENCHANTMENTS/STORED_ENCHANTMENTS), entrySet() of
    //                  Holder<Enchantment>; STILL the instance getFullname(level)+getDescriptionId() name API.
    //   >=1.21       : same component retrieval, but static Enchantment.getFullname(holder,level) +
    //                  holder.value().description() (the per-instance descriptionId/getFullname were removed).
    private fun enchantmentsBundles(): List<IteratorBundle> {
        val player = Minecraft.getInstance().player ?: return emptyList()
        val stack = player.mainHandItem
        if (stack.isEmpty) return emptyList()
        val out = ArrayList<IteratorBundle>()
        //? if >=1.21 {
        for (comp in arrayOf(net.minecraft.core.component.DataComponents.ENCHANTMENTS, net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS)) {
            val ench = stack.get(comp) ?: continue
            for (entry in ench.entrySet()) {
                enchBundle(out, net.minecraft.world.item.enchantment.Enchantment.getFullname(entry.key, entry.intValue).string,
                    entry.key.value().description().string, entry.intValue)
            }
        }
        //?}
        //? if >=1.20.5 && <1.21 {
        /*for (comp in arrayOf(net.minecraft.core.component.DataComponents.ENCHANTMENTS, net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS)) {
            val ench = stack.get(comp) ?: continue
            for (entry in ench.entrySet()) {
                val e = entry.key.value()
                enchBundle(out, e.getFullname(entry.intValue).string,
                    net.minecraft.client.resources.language.I18n.get(e.getDescriptionId()), entry.intValue)
            }
        }*///?}
        //? if <1.20.5 {
        /*for ((ench, level) in net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantments(stack)) {
            enchBundle(out, ench.getFullname(level).string,
                net.minecraft.client.resources.language.I18n.get(ench.getDescriptionId()), level)
        }*///?}
        return out
    }

    private fun enchBundle(out: MutableList<IteratorBundle>, full: String, base: String, level: Int) {
        out.add(IteratorBundle(Value.Str(full), linkedMapOf(
            "ENCHANTMENT" to Value.Str(full),
            "ENCHANTMENTNAME" to Value.Str(base),
            "ENCHANTMENTPOWER" to Value.Num(level),
        )))
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
        return SettingScale.toHuman("gamma", o.gamma().get()) // internal [0,1] -> brightness %
        //?}
        //? if <1.19 {
        /*return SettingScale.toHuman("gamma", o.gamma)*/
        //?}
    }

    private fun optSensitivity(o: net.minecraft.client.Options): Double {
        //? if >=1.19 {
        return SettingScale.toHuman("sensitivity", o.sensitivity().get()) // internal [0,1] -> 0-200
        //?}
        //? if <1.19 {
        /*return SettingScale.toHuman("sensitivity", o.sensitivity)*/
        //?}
    }

    /**
     * Current FPS. `getFps()` is absent on the 1.16.5-1.19.2 band, so read the classic F3 overlay
     * string `fpsString` ("120 fps T: ...") instead — until it was removed at 1.21.9, where getFps()
     * (present on the newest targets) takes over. Single split on 1.21.9 covers both gaps.
     */
    private fun currentFps(mc: Minecraft): Int {
        //? if >=1.21.9 {
        /*return mc.fps*/
        //?}
        //? if <1.21.9 {
        return mc.fpsString.trim().takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        //?}
    }

    /**
     * Item-cooldown percent, 0-100 (MKB VariableProviderPlayer.java:234 `round(getCooldownPercent *
     * 100)`; 0 = ready / no item, 100 = just used). `getCooldownPercent` took an `Item` until the
     * 1.21.2 arg change to `ItemStack` (boundary --continue-probe-confirmed: the Item form is
     * rejected on exactly 1.21.2+), so that arg is the only thing that splits. See the partialTick=0
     * rationale on the call below -- the same current-tick reasoning %ATTACKPOWER% now uses.
     */
    private fun cooldownPct(player: net.minecraft.world.entity.player.Player, stack: net.minecraft.world.item.ItemStack): Int {
        // partialTick = 0 (not 1.0): getCooldownPercent computes endTime - (tickCount + partialTick), so a
        // partialTick of 1.0 projects one tick ahead and makes %COOLDOWN% read 0 a tick early -- while the
        // item is still cooling. 0 gives the exact current-tick value (COOLDOWN==0 iff usable now),
        // tick-exact like itemUsePct/bowCharge. MKB passes its render partialTicks
        // (VariableProviderPlayer.java:234), which has no analogue in a per-tick script context.
        //? if >=1.21.2 {
        /*return Math.round(player.cooldowns.getCooldownPercent(stack, 0.0f) * 100f)*/
        //?}
        //? if <1.21.2 {
        return Math.round(player.cooldowns.getCooldownPercent(stack.item, 0.0f) * 100f)
        //?}
    }

    /**
     * Item-use progress, 0-100 (MKB VariableProviderPlayer.java:123 `round(itemUse / useMax * 100)`,
     * 0 when nothing is in use) — e.g. bow draw or eating. `ItemStack.getUseDuration()` gained a
     * LivingEntity param at 1.21 (--continue-probe-confirmed; one release before %COOLDOWN%'s 1.21.2
     * Item->ItemStack change), so the useMax read splits on 1.21.
     */
    private fun itemUsePct(player: net.minecraft.world.entity.player.Player): Int {
        val ticks = player.ticksUsingItem
        //? if >=1.21 {
        val useMax = player.useItem.getUseDuration(player)
        //?}
        //? if <1.21 {
        /*val useMax = player.useItem.getUseDuration()*/
        //?}
        return if (useMax != 0) Math.round(ticks.toFloat() / useMax * 100f) else 0
    }

    /**
     * Bow draw power, 0-100 (MKB VariableProviderPlayer.java:124 `round(getPowerForTime(itemUse) *
     * 100)` while a bow is drawn, else 0) — the non-linear charge curve, unlike %ITEMUSEPCT%.
     */
    private fun bowCharge(player: net.minecraft.world.entity.player.Player): Int =
        if (player.useItem.item == net.minecraft.world.item.Items.BOW)
            Math.round(net.minecraft.world.item.BowItem.getPowerForTime(player.ticksUsingItem) * 100f)
        else 0

    /**
     * Attack-strength recovery delay in ticks (MKB VariableProviderPlayer.java:157
     * `round(player.getCurrentItemAttackStrengthDelay())`; base unarmed = round(20/4.0) = 5 ticks,
     * matching MKB's no-player default of 5). Larger = slower weapon. MKB names it ATTACKSPEED though
     * it stores the cooldown period (the inverse of the attack-speed attribute).
     */
    private fun attackSpeed(player: net.minecraft.world.entity.player.Player): Int =
        Math.round(player.getCurrentItemAttackStrengthDelay())

    /**
     * Local (regional) difficulty x100 as an int, 0-675 (MKB VariableProviderPlayer.java:163
     * `(int)(getCurrentDifficultyAt(pos).getEffectiveDifficulty() * 100)`; the MKB obfuscated `.b()`
     * is getEffectiveDifficulty, confirmed against the 1.21.1 mojmap mapping where its official name
     * is literally `b`). This is the F3 "Local Difficulty" value (effective difficulty 0.0-6.75)
     * encoded x100 to an int, e.g. 345 = local difficulty 3.45; `.toInt()` truncates to match MKB's
     * `(int)` cast. Returns 0 when no level, and on 1.21.11 where getCurrentDifficultyAt moved to the
     * server-only ServerLevelAccessor (regional difficulty is not client-derivable there).
     */
    private fun localDifficulty(mc: Minecraft, player: net.minecraft.world.entity.player.Player): Int {
        //? if <1.21.11 {
        val level = mc.level ?: return 0
        return (level.getCurrentDifficultyAt(player.blockPosition()).getEffectiveDifficulty() * 100f).toInt()
        //?}
        //? if >=1.21.11 {
        /*return 0*/
        //?}
    }

    // Built-ins that need no player, so they resolve on the title / menu / connecting screen too.
    private fun envWithoutPlayer(mc: Minecraft, name: String): Value? = when (name) {
        "FOV" -> Value.Num(optFov(mc.options))
        "FPS" -> Value.Num(currentFps(mc)) // resolves on the title/menu screen too
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
        "SERVERMOTD" -> Value.Str(mc.currentServer?.motd?.string ?: "")
        "DATE" -> Value.Str(java.time.LocalDate.now().toString())
        "DATETIME" -> Value.Str(java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
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
