package dev.macromod.engine.action

import dev.macromod.engine.ScriptHost
import dev.macromod.engine.variable.VariableRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatCraftGuiActionTest {
    private class RecCraft : Crafting {
        val crafts = mutableListOf<Triple<String, Int, Boolean>>()
        val clicks = mutableListOf<Triple<Int, Int, Boolean>>()
        var cleared = 0
        override fun craft(item: String, amount: Int, wait: Boolean) { crafts.add(Triple(item, amount, wait)) }
        override fun clearCrafting() { cleared++ }
        override fun slotClick(slot: Int, button: Int, shift: Boolean) { clicks.add(Triple(slot, button, shift)) }
    }
    private class RecGui : GuiBuilder {
        val shown = mutableListOf<String>()
        val props = mutableMapOf<String, String>()
        val labels = mutableMapOf<String, String>()
        override fun showGui(screen: String) { shown.add(screen) }
        override fun getProperty(control: String, property: String) = "42"
        override fun setProperty(control: String, property: String, value: String) { props["$control.$property"] = value }
        override fun setLabel(name: String, text: String) { labels[name] = text }
    }
    private class RecFilter : ChatFilter {
        var enabled: Boolean? = null
        val mods = mutableListOf<String>()
        override fun setEnabled(enabled: Boolean) { this.enabled = enabled }
        override fun modify(message: String) { mods.add(message) }
    }
    private class Bridge(private val c: RecCraft, private val g: RecGui, private val f: RecFilter) : ClientBridge {
        override val crafting get() = c
        override val guiBuilder get() = g
        override val chatFilter get() = f
    }

    @Test fun `crafting actions route to the platform`() {
        val c = RecCraft()
        ScriptHost().run(
            "\$\${ craft(\"minecraft:torch\", 64); craftandwait(\"minecraft:stick\"); clearcrafting }\$\$",
            client = Bridge(c, RecGui(), RecFilter()),
        )
        assertEquals(Triple("minecraft:torch", 64, false), c.crafts[0])
        assertEquals(Triple("minecraft:stick", 1, true), c.crafts[1])
        assertEquals(1, c.cleared)
    }

    @Test fun `gui builder routes and getproperty captures`() {
        val g = RecGui(); val reg = VariableRegistry()
        ScriptHost().run(
            "\$\${ showgui(\"main\"); &p = getproperty(\"btn\", \"text\"); setproperty(\"btn\", \"text\", \"hi\") }\$\$",
            registry = reg, client = Bridge(RecCraft(), g, RecFilter()),
        )
        assertEquals(listOf("main"), g.shown)
        assertEquals("42", reg.getVariable("&p")!!.asString())
        assertEquals("hi", g.props["btn.text"])
    }

    @Test fun `setlabel normalises section codes to ampersands in the text`() {
        val g = RecGui()
        ScriptHost().run("\$\${ setlabel(\"title\", \"§ahi §lthere\") }\$\$", client = Bridge(RecCraft(), g, RecFilter()))
        assertEquals("&ahi &lthere", g.labels["title"]) // §->& per ScriptActionSetLabel.java:19; the name is left untouched
    }

    @Test fun `chatfilter routes enable + modify`() {
        val f = RecFilter()
        ScriptHost().run("\$\${ chatfilter(true); modify(\"hi\") }\$\$", client = Bridge(RecCraft(), RecGui(), f))
        assertEquals(true, f.enabled)
        assertEquals(listOf("hi"), f.mods)
    }

    @Test fun `chatfilter enables on the on keyword and disables on off`() {
        // MKB enables on the literal "on"; our old asBoolean returned false for it.
        val on = RecFilter()
        ScriptHost().run("\$\${ chatfilter(\"on\") }\$\$", client = Bridge(RecCraft(), RecGui(), on))
        assertEquals(true, on.enabled)

        val off = RecFilter()
        ScriptHost().run("\$\${ chatfilter(\"off\") }\$\$", client = Bridge(RecCraft(), RecGui(), off))
        assertEquals(false, off.enabled)
    }

    @Test fun `modify converts ampersand colour codes to section codes`() {
        val f = RecFilter()
        ScriptHost().run("\$\${ modify(\"&ahi\") }\$\$", client = Bridge(RecCraft(), RecGui(), f))
        assertEquals(listOf("§ahi"), f.mods) // &->§ per ScriptActionModify.java:21 (convertAmpCodes)
    }

    @Test fun `setproperty normalises section codes to ampersands in the value`() {
        val g = RecGui()
        ScriptHost().run("\$\${ setproperty(\"btn\", \"text\", \"§ahi\") }\$\$", client = Bridge(RecCraft(), g, RecFilter()))
        assertEquals("&ahi", g.props["btn.text"]) // §->& per ScriptActionSetProperty.java:32; names untouched
    }

    @Test fun `slotclick parses the mouse button as a string`() {
        val c = RecCraft()
        ScriptHost().run("\$\${ slotclick(0, right); slotclick(1, 0) }\$\$", client = Bridge(c, RecGui(), RecFilter()))
        assertEquals(1, c.clicks[0].second) // "right" -> button 1 (ScriptActionSlotClick.java:42-43)
        assertEquals(0, c.clicks[1].second) // "0" -> button 0
    }
}
