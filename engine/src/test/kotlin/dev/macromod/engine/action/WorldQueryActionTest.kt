package dev.macromod.engine.action

import dev.macromod.engine.ScriptHost
import dev.macromod.engine.variable.VariableRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorldQueryActionTest {
    private class FakeQuery : WorldQuery {
        override fun blockAt(x: Int, y: Int, z: Int) = "minecraft:stone"
        override fun findSlot(item: String) = if (item == "minecraft:diamond") 3 else -1
        override fun itemInSlot(slot: Int) = "minecraft:dirt"
        override fun pick(items: List<String>) = "minecraft:sword" in items
        override fun trace(distance: Int) = "minecraft:grass_block"
    }
    private class Bridge(private val q: FakeQuery) : ClientBridge { override val query get() = q }

    private fun runQ(body: String): VariableRegistry {
        val reg = VariableRegistry()
        ScriptHost().run("\$\${ $body }\$\$", registry = reg, client = Bridge(FakeQuery()))
        return reg
    }

    @Test fun `getslot writes the slot index`() {
        assertEquals(3, runQ("getslot(\"minecraft:diamond\", #s)").getVariable("#s")!!.asInt())
    }

    @Test fun `getid captures the block registry id`() {
        assertEquals("minecraft:stone", runQ("&b = getid(1, 2, 3)").getVariable("&b")!!.asString())
    }

    @Test fun `trace returns the looked-at id`() {
        assertEquals("minecraft:grass_block", runQ("&t = trace(5)").getVariable("&t")!!.asString())
    }

    @Test fun `pick returns whether an item matched`() {
        assertTrue(runQ("ok = pick(\"minecraft:sword\")").getVariable("ok")!!.asBoolean())
    }

    @Test fun `itemid echoes the registry id (legacy adaptation)`() {
        // no bridge needed: itemid is an identity adaptation post-flattening
        val reg = VariableRegistry()
        ScriptHost().run("\$\${ &i = itemid(\"minecraft:stone\") }\$\$", registry = reg)
        assertEquals("minecraft:stone", reg.getVariable("&i")!!.asString())
    }
}
