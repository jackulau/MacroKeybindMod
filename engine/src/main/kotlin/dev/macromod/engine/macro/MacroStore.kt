package dev.macromod.engine.macro

/**
 * Serialises a [MacroRegistry] to/from a plain-text, human-editable format and back —
 * the persistence behind per-server config files (the original mod's `.macros.txt`).
 *
 * The format is property-style so script values (which contain `$`, `"`, `|`, `;`) need
 * no escaping — everything after `=` is taken verbatim:
 *
 * ```
 * # MacroKeybindMod bindings
 * Macro[0].trigger=key:72
 * Macro[0].mode=keystate
 * Macro[0].enabled=true
 * Macro[0].name=Auto attack
 * Macro[0].script=$${ key(attack) }$$
 * Macro[0].keyHeld=$${ key(attack) }$$
 * Macro[0].repeatRate=200
 * ```
 *
 * KEYSTATE adds `keyHeld` / `keyUp` / `repeatRate`; CONDITIONAL adds `condition` (its `keyUp` is the
 * else-branch); any binding may add `ctrl` / `alt` / `shift` = `1` for a modifier requirement. These keys
 * are written only when set, so a plain ONESHOT binding stays minimal, and any absent key loads to its
 * default (empty script / 1000 ms / no requirement).
 *
 * The actual file read/write is a thin Fabric-side wrapper; this string<->registry core
 * is pure and unit-tested. Standalone `.txt` scripts (e.g. macromod.market exports) need
 * no parsing here — their text is a macro source, run directly via `ScriptHost.compile`.
 */
object MacroStore {

    fun save(registry: MacroRegistry): String {
        val sb = StringBuilder("# MacroKeybindMod bindings\n")
        registry.all().forEachIndexed { i, b ->
            val trigger = when (val t = b.trigger) {
                is Trigger.Key -> "key:${t.keyCode}"
                is Trigger.Mouse -> "mouse:${t.button}"
                is Trigger.Event -> "event:${t.name}"
            }
            sb.append("Macro[$i].trigger=").append(trigger).append('\n')
            sb.append("Macro[$i].mode=").append(b.mode.name.lowercase()).append('\n')
            sb.append("Macro[$i].enabled=").append(b.enabled).append('\n')
            sb.append("Macro[$i].name=").append(b.name).append('\n')
            sb.append("Macro[$i].script=").append(b.script).append('\n')
            // The non-one-shot fields are written only when set, so a plain ONESHOT binding stays
            // minimal; absent keys round-trip to their defaults on load.
            if (b.keyHeldScript.isNotEmpty()) sb.append("Macro[$i].keyHeld=").append(b.keyHeldScript).append('\n')
            if (b.keyUpScript.isNotEmpty()) sb.append("Macro[$i].keyUp=").append(b.keyUpScript).append('\n')
            if (b.condition.isNotEmpty()) sb.append("Macro[$i].condition=").append(b.condition).append('\n')
            if (b.repeatRateMs != 1000L) sb.append("Macro[$i].repeatRate=").append(b.repeatRateMs).append('\n')
            // Modifier requirements (MKB ctrl/alt/shift), written only when required so a plain binding stays minimal.
            if (b.requireCtrl) sb.append("Macro[$i].ctrl=1\n")
            if (b.requireAlt) sb.append("Macro[$i].alt=1\n")
            if (b.requireShift) sb.append("Macro[$i].shift=1\n")
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
                    keyHeldScript = fields["keyHeld"] ?: "",
                    keyUpScript = fields["keyUp"] ?: "",
                    condition = fields["condition"] ?: "",
                    repeatRateMs = fields["repeatRate"]?.toLongOrNull() ?: 1000L,
                    requireCtrl = fields["ctrl"] == "1",
                    requireAlt = fields["alt"] == "1",
                    requireShift = fields["shift"] == "1",
                ),
            )
        }
        return registry
    }

    private fun parseTrigger(value: String): Trigger? = when {
        value.startsWith("key:") -> value.removePrefix("key:").trim().toIntOrNull()?.let { Trigger.Key(it) }
        value.startsWith("mouse:") -> value.removePrefix("mouse:").trim().toIntOrNull()?.let { Trigger.Mouse(it) }
        value.startsWith("event:") -> Trigger.Event(value.removePrefix("event:"))
        else -> null
    }

    private fun parseMode(value: String?): PlaybackMode =
        runCatching { PlaybackMode.valueOf((value ?: "oneshot").uppercase()) }.getOrDefault(PlaybackMode.ONESHOT)

    private val LINE = Regex("^Macro\\[(\\d+)]\\.(\\w+)=(.*)$")
}
