package dev.macromod.fabric

import dev.macromod.engine.action.ChatFilter

/**
 * Chat-filter state shared between the onFilterableChat handler (which reads it) and the
 * filter / modify actions (which set it). A bound onFilterableChat macro calls filter() to suppress
 * the current line; the Fabric ALLOW_CHAT handler reads [suppressed] to decide whether to hide it.
 * [reset] runs before each message so suppression never leaks to the next line, and with no filter
 * macro bound nothing is ever suppressed (the handler returns allow=true).
 */
class FabricChatFilter(private val feedback: (String) -> Unit) : ChatFilter {
    var suppressed = false
        private set
    var modified: String? = null
        private set

    fun reset() {
        suppressed = false
        modified = null
    }

    override fun setEnabled(enabled: Boolean) { feedback("[chatfilter] ${if (enabled) "on" else "off"}") }
    override fun filter() { suppressed = true }
    override fun modify(message: String) { modified = message }
}
