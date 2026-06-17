package dev.macromod.engine.macro

/**
 * Serialises a [MacroRegistry] to/from a plain-text, human-editable format and back —
 * the persistence behind per-server config files (the original mod's `.macros.txt`).
 *
 * The format is property-style so script values (which contain `$`, `"`, `|`, `;`) need
 * no escaping — everything after `=` is taken verbatim:
 *
 * ```
 * # MacroMod bindings
 * Macro[0].trigger=key:72
 * Macro[0].mode=keystate
 * Macro[0].enabled=true
 * Macro[0].name=Auto attack
 * Macro[0].script=$${ key(attack) }$$
 * ```
 *
 * The actual file read/write is a thin Fabric-side wrapper; this string<->registry core
 * is pure and unit-tested. Standalone `.txt` scripts (e.g. macromod.market exports) need
 * no parsing here — their text is a macro source, run directly via `ScriptHost.compile`.
 */
object MacroStore {

    fun save(registry: MacroRegistry): String {
        val sb = StringBuilder("# MacroMod bindings\n")
        registry.all().forEachIndexed { i, b ->
            val trigger = when (val t = b.trigger) {
                is Trigger.Key -> "key:${t.keyCode}"
                is Trigger.Event -> "event:${t.name}"
            }
            sb.append("Macro[$i].trigger=").append(trigger).append('\n')
            sb.append("Macro[$i].mode=").append(b.mode.name.lowercase()).append('\n')
            sb.append("Macro[$i].enabled=").append(b.enabled).append('\n')
            sb.append("Macro[$i].name=").append(b.name).append('\n')
            sb.append("Macro[$i].script=").append(b.script).append('\n')
        }
        return sb.toString()
    }

    fun load(text: String): MacroRegistry {
        val byIndex = sortedMapOf<Int, MutableMap<String, String>>()
        for (line in text.lineSequence()) {
            val m = LINE.matchEntire(line) ?: continue
            byIndex.getOrPut(m.groupValues[1].toInt()) { mutableMapOf() }[m.groupValues[2]] = m.groupValues[3]
        }
        val registry = MacroRegistry()
        for ((_, fields) in byIndex) {
            val trigger = parseTrigger(fields["trigger"] ?: continue) ?: continue
            registry.add(
                MacroBinding(
                    trigger = trigger,
                    script = fields["script"] ?: "",
                    mode = parseMode(fields["mode"]),
                    name = fields["name"] ?: "",
                    enabled = (fields["enabled"] ?: "true").toBoolean(),
                ),
            )
        }
        return registry
    }

    private fun parseTrigger(value: String): Trigger? = when {
        value.startsWith("key:") -> value.removePrefix("key:").trim().toIntOrNull()?.let { Trigger.Key(it) }
        value.startsWith("event:") -> Trigger.Event(value.removePrefix("event:"))
        else -> null
    }

    private fun parseMode(value: String?): PlaybackMode =
        runCatching { PlaybackMode.valueOf((value ?: "oneshot").uppercase()) }.getOrDefault(PlaybackMode.ONESHOT)

    private val LINE = Regex("^Macro\\[(\\d+)]\\.(\\w+)=(.*)$")
}
