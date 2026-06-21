package dev.macromod.engine.macro

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MacroStoreTest {
    @Test fun `round-trips bindings including special characters`() {
        val registry = MacroRegistry()
        registry.add(MacroBinding(Trigger.Key(72), "\$\${ log(\"a|b\"); key(attack) }\$\$", PlaybackMode.KEYSTATE, "My Macro"))
        registry.add(MacroBinding(Trigger.Event("onTick"), "\$\${ inc(@#t) }\$\$"))
        registry.add(MacroBinding(Trigger.Key(73), "/home", PlaybackMode.ONESHOT, "", enabled = false))

        val loaded = MacroStore.load(MacroStore.save(registry))
        assertEquals(registry.all(), loaded.all())
    }

    @Test fun `ignores comments and junk lines`() {
        val loaded = MacroStore.load(
            """
            # a comment
            some random junk
            Macro[0].trigger=key:5
            Macro[0].script=/spawn
            """.trimIndent(),
        )
        assertEquals(1, loaded.all().size)
        assertEquals(Trigger.Key(5), loaded.all()[0].trigger)
        assertEquals("/spawn", loaded.all()[0].script)
    }

    @Test fun `tolerates a macro index past Int range without crashing`() {
        // The format is explicitly human-editable, so a hand-edited or corrupt file may carry a macro
        // index wider than Int. load() must skip that line like any other junk (every other parse here
        // is already lenient), never throw NumberFormatException and lose ALL bindings with it.
        val loaded = MacroStore.load(
            """
            Macro[0].trigger=key:5
            Macro[0].script=/spawn
            Macro[99999999999].trigger=key:6
            Macro[99999999999].script=/boom
            """.trimIndent(),
        )
        assertEquals(1, loaded.all().size)                  // the valid binding survives; the over-range line is skipped
        assertEquals(Trigger.Key(5), loaded.all()[0].trigger)
    }

    @Test fun `event triggers round-trip by name`() {
        val registry = MacroRegistry()
        registry.add(MacroBinding(Trigger.Event("onChat"), "\$\${ log(\"%CHAT%\") }\$\$"))
        val loaded = MacroStore.load(MacroStore.save(registry))
        assertEquals(Trigger.Event("onChat"), loaded.all()[0].trigger)
    }

    @Test fun `empty registry produces no bindings`() {
        assertTrue(MacroStore.load(MacroStore.save(MacroRegistry())).all().isEmpty())
    }

    @Test fun `round-trips the keystate and conditional fields (keyHeld, keyUp, condition, repeatRate)`() {
        val registry = MacroRegistry()
        registry.add(MacroBinding(
            Trigger.Key(74), "\$\${ key(\"attack\") }\$\$",
            mode = PlaybackMode.KEYSTATE,
            name = "Hold to attack",
            keyHeldScript = "\$\${ key(\"attack\") }\$\$",
            keyUpScript = "\$\${ log(\"released\") }\$\$",
            repeatRateMs = 250,
        ))
        registry.add(MacroBinding(
            Trigger.Key(75), "\$\${ log(\"on\") }\$\$",
            mode = PlaybackMode.CONDITIONAL,
            condition = "%HEALTH% < 10",
            keyUpScript = "\$\${ log(\"off\") }\$\$",
        ))

        val loaded = MacroStore.load(MacroStore.save(registry))
        assertEquals(registry.all(), loaded.all())   // data-class equality covers every field, incl. the new ones

        // Guard the equality above isn't passing on shared defaults: the fields really hit the text.
        val saved = MacroStore.save(registry)
        assertTrue(saved.contains("keyHeld="), saved)
        assertTrue(saved.contains("keyUp="), saved)
        assertTrue(saved.contains("condition=%HEALTH% < 10"), saved)
        assertTrue(saved.contains("repeatRate=250"), saved)
    }

    @Test fun `a default one-shot binding serializes none of the keystate or conditional keys`() {
        val registry = MacroRegistry()
        registry.add(MacroBinding(Trigger.Key(76), "/spawn"))   // all-default ONESHOT (repeatRate = 1000)
        val saved = MacroStore.save(registry)
        assertTrue(!saved.contains("keyHeld=") && !saved.contains("keyUp="), saved)
        assertTrue(!saved.contains("condition=") && !saved.contains("repeatRate="), saved)
    }

    @Test fun `mouse-button triggers round-trip by button`() {
        val registry = MacroRegistry()
        registry.add(MacroBinding(Trigger.Mouse(4), "\$\${ log(\"side button\") }\$\$", PlaybackMode.KEYSTATE, "Side"))
        val saved = MacroStore.save(registry)
        assertTrue(saved.contains("mouse:4"), saved)            // serialized as mouse:<button>, distinct from key:<code>
        val loaded = MacroStore.load(saved)
        assertEquals(Trigger.Mouse(4), loaded.all()[0].trigger)
        assertEquals(registry.all(), loaded.all())
    }

    @Test fun `modifier requirements round-trip and a plain binding writes none`() {
        val registry = MacroRegistry()
        registry.add(MacroBinding(Trigger.Key(70), "\$\${ log(\"ctrlShiftG\") }\$\$", requireCtrl = true, requireShift = true))
        registry.add(MacroBinding(Trigger.Key(71), "/plain"))   // no modifier requirement
        val saved = MacroStore.save(registry)
        assertTrue(saved.contains("ctrl=1") && saved.contains("shift=1"), saved)
        assertTrue(!saved.contains("alt=1"), saved)                          // alt not required -> never written
        assertEquals(1, saved.lines().count { it.endsWith(".ctrl=1") })      // only binding 0 wrote it; the plain binding stays minimal
        val loaded = MacroStore.load(saved)
        assertEquals(registry.all(), loaded.all())                          // data-class equality covers the new fields
    }
}
