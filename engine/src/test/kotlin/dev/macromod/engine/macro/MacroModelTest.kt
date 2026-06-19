package dev.macromod.engine.macro

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test fun `hasEvent matches enabled event bindings without allocating a list`() {
        // the Fabric tick loop calls hasEvent every tick to gate expensive event-watcher work
        val r = MacroRegistry()
        r.add(MacroBinding(Trigger.Event("onPickupItem"), "x"))
        r.add(MacroBinding(Trigger.Event("onChat"), "y", enabled = false))
        assertTrue(r.hasEvent("onpickupitem"))  // case-insensitive
        assertTrue(r.hasEvent("ONPICKUPITEM"))
        assertFalse(r.hasEvent("onChat"))        // disabled binding does not count
        assertFalse(r.hasEvent("onShowGui"))     // nothing bound
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

    @Test fun `engine macros follow the active config profile`() {
        // per-server switching: the engine's live binds are whichever profile is active
        val cfg = ConfigManager()
        cfg.config("a").registry.add(MacroBinding(Trigger.Key(1), "x"))
        cfg.config("b").registry.add(MacroBinding(Trigger.Event("onChat"), "y"))
        val engine = MacroEngine(configs = cfg)
        cfg.switchTo("a")
        assertEquals(1, engine.macros.forKey(1).size)
        assertFalse(engine.macros.hasEvent("onChat"))
        cfg.switchTo("b")
        assertTrue(engine.macros.forKey(1).isEmpty())  // profile A binds no longer live
        assertTrue(engine.macros.hasEvent("onChat"))   // profile B binds now live
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
