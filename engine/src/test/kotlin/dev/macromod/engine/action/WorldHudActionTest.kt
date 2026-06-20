package dev.macromod.engine.action

import dev.macromod.engine.ScriptHost
import kotlin.test.Test
import kotlin.test.assertEquals

class WorldHudActionTest {
    private class RecWorld : WorldActions {
        var respawned = 0
        val sounds = mutableListOf<String>()
        val signs = mutableListOf<List<String>>()
        override fun respawn() { respawned++ }
        override fun playSound(sound: String) { sounds.add(sound) }
        override fun placeSign(lines: List<String>) { signs.add(lines) }
    }
    private class RecHud : Hud {
        val titles = mutableListOf<Pair<String, String>>()
        val popups = mutableListOf<String>()
        val toasts = mutableListOf<Pair<String, String>>()
        val guis = mutableListOf<String>()
        override fun title(title: String, subtitle: String) { titles.add(title to subtitle) }
        override fun popup(message: String) { popups.add(message) }
        override fun toast(title: String, description: String) { toasts.add(title to description) }
        override fun openGui(name: String) { guis.add(name) }
    }
    private class Bridge(private val w: RecWorld, private val h: RecHud) : ClientBridge {
        override val world get() = w
        override val hud get() = h
    }

    @Test fun `world and hud actions route to the bridge`() {
        val w = RecWorld(); val h = RecHud()
        ScriptHost().run(
            "\$\${ respawn; playsound(\"minecraft:click\"); placesign(\"a\", \"b\"); " +
                "title(\"Hi\", \"there\"); popupmessage(\"pop\"); gui(\"inventory\") }\$\$",
            client = Bridge(w, h),
        )
        assertEquals(1, w.respawned)
        assertEquals(listOf("minecraft:click"), w.sounds)
        assertEquals(listOf(listOf("a", "b")), w.signs)
        assertEquals(listOf("Hi" to "there"), h.titles)
        assertEquals(listOf("pop"), h.popups)
        assertEquals(listOf("inventory"), h.guis)
    }

    @Test fun `hud display text converts ampersand colour codes`() {
        // MKB convertAmpCodes on title (params 0,1), toast text lines (params 2,3), popupmessage (param 0).
        val w = RecWorld(); val h = RecHud()
        ScriptHost().run(
            "\$\${ title(\"&aHi\", \"&bthere\"); popupmessage(\"&cpop\"); " +
                "toast(\"info\", \"icon\", \"&dt1\", \"&et2\") }\$\$",
            client = Bridge(w, h),
        )
        assertEquals(listOf("§aHi" to "§bthere"), h.titles)
        assertEquals(listOf("§cpop"), h.popups)
        assertEquals(listOf("§dt1" to "§et2"), h.toasts)
    }
}
