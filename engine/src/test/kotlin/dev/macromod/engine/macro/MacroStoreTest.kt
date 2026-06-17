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

    @Test fun `event triggers round-trip by name`() {
        val registry = MacroRegistry()
        registry.add(MacroBinding(Trigger.Event("onChat"), "\$\${ log(\"%CHAT%\") }\$\$"))
        val loaded = MacroStore.load(MacroStore.save(registry))
        assertEquals(Trigger.Event("onChat"), loaded.all()[0].trigger)
    }

    @Test fun `empty registry produces no bindings`() {
        assertTrue(MacroStore.load(MacroStore.save(MacroRegistry())).all().isEmpty())
    }
}
