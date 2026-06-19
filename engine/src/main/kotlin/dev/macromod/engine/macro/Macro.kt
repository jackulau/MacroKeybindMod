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
    /** A keyboard key or mouse button, identified by its platform key code. */
    data class Key(val keyCode: Int) : Trigger

    /** A named event (e.g. `onTick`, `onChat`), matched case-insensitively. */
    data class Event(val name: String) : Trigger
}

/**
 * How a key-bound macro plays:
 *  - [ONESHOT]      run once per press
 *  - [KEYSTATE]     run repeatedly while the key is held; a key-up script may also run
 *  - [CONDITIONAL]  evaluated every tick; runs while its condition holds
 */
enum class PlaybackMode { ONESHOT, KEYSTATE, CONDITIONAL }

/** A single bound macro: trigger → script, with a playback mode. */
data class MacroBinding(
    val trigger: Trigger,
    val script: String,
    val mode: PlaybackMode = PlaybackMode.ONESHOT,
    val name: String = "",
    val enabled: Boolean = true,
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

    private fun resolve(server: String?): String? {
        if (server == null) return null
        val full = server.lowercase()
        serverToConfig[full]?.let { return it }
        val hostOnly = full.substringBefore(':')
        return serverToConfig[hostOnly]
    }
}
