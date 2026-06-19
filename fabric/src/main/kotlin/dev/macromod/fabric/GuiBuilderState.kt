package dev.macromod.fabric

/**
 * Mutable backing state for the engine's custom-GUI actions (`setlabel` / `setproperty` / `getproperty`).
 * The bridge writes here; [dev.macromod.fabric.ui.CustomGuiScreen] reads it when `showgui` opens the
 * screen. Insertion order is preserved so labels render in the order the script declared them.
 */
class GuiBuilderState {
    var title: String = "MacroKeybindMod GUI"
    val labels = LinkedHashMap<String, String>()
    val properties = LinkedHashMap<String, String>()

    fun setLabel(name: String, text: String) { labels[name] = text }
    fun setProperty(control: String, property: String, value: String) { properties["$control.$property"] = value }
    fun getProperty(control: String, property: String): String = properties["$control.$property"] ?: ""

    fun labelPairs(): List<Pair<String, String>> = labels.entries.map { it.key to it.value }
    fun propertyPairs(): List<Pair<String, String>> = properties.entries.map { it.key to it.value }
}
