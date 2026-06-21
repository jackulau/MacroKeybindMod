package dev.macromod.engine.macro

/**
 * The platform-agnostic macro/keybind model.
 *
 * A *binding* ties a [Trigger] (a key, mouse button, or named event) to a script and a
 * [PlaybackMode]. The Fabric layer feeds real key/mouse/event signals into a
 * [MacroRegistry] to find which scripts to run; the model itself has no Minecraft
 * dependency, so it's fully unit-testable.
 */
sealed interface Trigger {
    /** A keyboard key, identified by its GLFW key code (`GLFW.GLFW_KEY_*`). */
    data class Key(val keyCode: Int) : Trigger

    /**
     * A mouse button, identified by its GLFW button code (`GLFW.GLFW_MOUSE_BUTTON_*`). Kept a distinct
     * subtype from [Key] because GLFW key codes and mouse-button codes (0-7) overlap, so a single int
     * cannot tell them apart. (MKB packed both into one KEY trigger via reserved id ranges; we carry the
     * kind in the type instead, per ARCHITECTURE-REFERENCE's own-id-space design.)
     */
    data class Mouse(val button: Int) : Trigger

    /** A named event (e.g. `onTick`, `onChat`), matched case-insensitively. */
    data class Event(val name: String) : Trigger
}

/**
 * How a key-bound macro plays (MKB MacroPlaybackType):
 *  - [ONESHOT]      run once per key press.
 *  - [KEYSTATE]     run the key-down [MacroBinding.script] on press, the [MacroBinding.keyHeldScript]
 *                   repeatedly while the key is held (throttled by [MacroBinding.repeatRateMs]), and the
 *                   [MacroBinding.keyUpScript] once on release.
 *  - [CONDITIONAL]  evaluated once on press: run the main script if the condition holds, else the key-up
 *                   (else) script. MKB `preCompileConditionalMacro` — a per-press branch, NOT an
 *                   every-tick loop.
 */
enum class PlaybackMode { ONESHOT, KEYSTATE, CONDITIONAL }

/**
 * A single bound macro: a [trigger] → [script] (the key-down / one-shot script), with a [PlaybackMode].
 *
 * The extra scripts back the non-one-shot modes (MKB Macro keyDownMacro/keyHeldMacro/keyUpMacro + condition):
 *  - [keyHeldScript]  KEYSTATE: re-run every [repeatRateMs] ms while the key is held.
 *  - [keyUpScript]    KEYSTATE: run once on key release. CONDITIONAL: the else-branch.
 *  - [condition]      CONDITIONAL: evaluated once on press; run [script] if it holds, else [keyUpScript].
 *  - [repeatRateMs]   KEYSTATE held-repeat throttle (MKB default 1000 ms, Macro.java:50).
 */
data class MacroBinding(
    val trigger: Trigger,
    val script: String,
    val mode: PlaybackMode = PlaybackMode.ONESHOT,
    val name: String = "",
    val enabled: Boolean = true,
    val keyHeldScript: String = "",
    val keyUpScript: String = "",
    val condition: String = "",
    val repeatRateMs: Long = 1000,
)

/** A set of bindings (one keybind layout). Looked up by key code or event name. */
class MacroRegistry {
    private val bindings = ArrayList<MacroBinding>()

    fun add(binding: MacroBinding): MacroBinding {
        bindings.add(binding)
        return binding
    }

    fun remove(binding: MacroBinding): Boolean = bindings.remove(binding)

    fun clear() = bindings.clear()

    fun all(): List<MacroBinding> = bindings.toList()

    /** Enabled bindings whose trigger is the given key. */
    fun forKey(keyCode: Int): List<MacroBinding> =
        bindings.filter { it.enabled && it.trigger is Trigger.Key && (it.trigger as Trigger.Key).keyCode == keyCode }

    /** Enabled bindings whose trigger is the given mouse button. */
    fun forMouse(button: Int): List<MacroBinding> =
        bindings.filter { it.enabled && it.trigger is Trigger.Mouse && (it.trigger as Trigger.Mouse).button == button }

    /** Enabled bindings whose trigger is the named event (case-insensitive). */
    fun forEvent(name: String): List<MacroBinding> =
        bindings.filter { it.enabled && it.trigger is Trigger.Event && (it.trigger as Trigger.Event).name.equals(name, ignoreCase = true) }

    /**
     * Whether any enabled binding listens for [name] — allocation-free (unlike `forEvent(...).isNotEmpty()`,
     * which builds a list). The Fabric tick loop calls this every tick to skip expensive event-watcher
     * work (inventory scans, Component renders) when nothing is bound, so an idle client stays cheap.
     */
    fun hasEvent(name: String): Boolean =
        bindings.any { it.enabled && it.trigger is Trigger.Event && (it.trigger as Trigger.Event).name.equals(name, ignoreCase = true) }

    /**
     * Whether any enabled binding is triggered by a pollable input — a keyboard key OR a mouse button —
     * allocation-free, mirroring [hasEvent]. The Fabric tick loop guards the per-tick input poll
     * ([MacroEngine.tickKeys]) with this so a config with no input bindings costs nothing.
     */
    fun hasInputBindings(): Boolean =
        bindings.any { it.enabled && (it.trigger is Trigger.Key || it.trigger is Trigger.Mouse) }
}

/** A named configuration profile holding its own keybind layout. */
class MacroConfig(val name: String, val registry: MacroRegistry = MacroRegistry())

/**
 * Manages configuration profiles and per-server auto-switching, mirroring the original
 * mod's behaviour of swapping keybind layouts when you join different servers.
 *
 * Server lookup tries the full `host` first, then the host with its port stripped, then
 * falls back to the default profile.
 */
class ConfigManager {
    val default = MacroConfig("default")
    private val configs = linkedMapOf("default" to default)
    private val serverToConfig = HashMap<String, String>()

    var active: MacroConfig = default
        private set

    /** Get (creating if needed) a named profile. */
    fun config(name: String): MacroConfig = configs.getOrPut(name) { MacroConfig(name) }

    fun configs(): List<MacroConfig> = configs.values.toList()

    /** Bind a server address (host or host:port) to a profile name. */
    fun mapServer(server: String, configName: String) {
        serverToConfig[server.lowercase()] = configName
        config(configName) // ensure it exists
    }

    /** Switch the active profile based on the joined server (null = single-player → default). */
    fun switchToServer(server: String?): MacroConfig {
        active = resolve(server)?.let { config(it) } ?: default
        return active
    }

    /** Switch the active profile by name (creating it if needed). */
    fun switchTo(name: String): MacroConfig {
        active = config(name)
        return active
    }

    private fun resolve(server: String?): String? {
        if (server == null) return null
        val full = server.lowercase()
        serverToConfig[full]?.let { return it }
        val hostOnly = full.substringBefore(':')
        return serverToConfig[hostOnly]
    }
}
