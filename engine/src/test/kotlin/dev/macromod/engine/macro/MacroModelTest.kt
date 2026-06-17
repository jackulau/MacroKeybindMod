package dev.macromod.engine.macro

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MacroModelTest {
    @Test fun `lookup bindings by key`() {
        val r = MacroRegistry()
        r.add(MacroBinding(Trigger.Key(65), "log(\"a\")"))
        r.add(MacroBinding(Trigger.Key(65), "log(\"b\")", mode = PlaybackMode.KEYSTATE))
        r.add(MacroBinding(Trigger.Key(66), "log(\"c\")"))
        assertEquals(2, r.forKey(65).size)
        assertEquals(1, r.forKey(66).size)
        assertTrue(r.forKey(99).isEmpty())
    }

    @Test fun `lookup by event is case-insensitive`() {
        val r = MacroRegistry()
        r.add(MacroBinding(Trigger.Event("onTick"), "x"))
        assertEquals(1, r.forEvent("ontick").size)
        assertEquals(1, r.forEvent("ONTICK").size)
        assertTrue(r.forEvent("onChat").isEmpty())
    }

    @Test fun `disabled bindings are skipped`() {
        val r = MacroRegistry()
        r.add(MacroBinding(Trigger.Key(1), "x", enabled = false))
        assertTrue(r.forKey(1).isEmpty())
    }

    @Test fun `remove and clear`() {
        val r = MacroRegistry()
        val b = r.add(MacroBinding(Trigger.Key(1), "x"))
        assertTrue(r.remove(b))
        assertTrue(r.forKey(1).isEmpty())
        r.add(MacroBinding(Trigger.Key(2), "y"))
        r.clear()
        assertTrue(r.all().isEmpty())
    }

    @Test fun `config manager defaults to the default profile`() {
        val m = ConfigManager()
        assertEquals("default", m.active.name)
        assertEquals(m.default, m.switchToServer(null))
    }

    @Test fun `per-server switch by host and host-port`() {
        val m = ConfigManager()
        m.mapServer("hypixel.net", "skyblock")
        assertEquals("skyblock", m.switchToServer("hypixel.net").name)
        assertEquals("skyblock", m.switchToServer("hypixel.net:25565").name) // port stripped
        assertEquals("default", m.switchToServer("example.com").name) // unknown → default
    }

    @Test fun `profiles hold independent layouts`() {
        val m = ConfigManager()
        m.config("a").registry.add(MacroBinding(Trigger.Key(1), "x"))
        m.config("b").registry.add(MacroBinding(Trigger.Key(2), "y"))
        assertEquals(1, m.config("a").registry.forKey(1).size)
        assertTrue(m.config("a").registry.forKey(2).isEmpty())
    }
}
