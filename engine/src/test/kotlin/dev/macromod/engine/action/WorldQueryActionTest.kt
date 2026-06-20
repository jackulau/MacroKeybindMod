package dev.macromod.engine.action

import dev.macromod.engine.ScriptHost
import dev.macromod.engine.value.Value
import dev.macromod.engine.variable.VariableRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorldQueryActionTest {
    private class FakeQuery : WorldQuery {
        override fun blockAt(x: Int, y: Int, z: Int) = "minecraft:stone"
        override fun findSlot(item: String) = if (item == "minecraft:diamond") 3 else -1
        override fun itemInSlot(slot: Int) = "minecraft:dirt"
        override fun slotItem(slot: Int) = SlotItem("minecraft:dirt", 42, 7)
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

    @Test fun `getslotitem writes the item id to the out-var`() {
        // MKB GETSLOTITEM(<slotid>,<#idvar>,...) writes the id into the out-var (ScriptActionGetSlotItem.java:36-37).
        assertEquals("minecraft:dirt", runQ("getslotitem(3, &it)").getVariable("&it")!!.asString())
    }

    @Test fun `getslotitem writes stack count and damage to the out-vars`() {
        // MKB GETSLOTITEM(<slot>,<#id>,<#stack>,<#data>) also writes count=slotStack.E() + damage=slotStack.j()
        // (ScriptActionGetSlotItem.java:40-45). One slot fetch feeds all three out-vars.
        val reg = runQ("getslotitem(3, &id, #n, #d)")
        assertEquals("minecraft:dirt", reg.getVariable("&id")!!.asString())
        assertEquals(42, reg.getVariable("#n")!!.asInt())
        assertEquals(7, reg.getVariable("#d")!!.asInt())
    }

    @Test fun `getid captures the block registry id`() {
        assertEquals("minecraft:stone", runQ("&b = getid(1, 2, 3)").getVariable("&b")!!.asString())
    }

    @Test fun `getid resolves tilde-relative coordinates against the player position`() {
        val seen = mutableListOf<Triple<Int, Int, Int>>()
        val q = object : WorldQuery {
            override fun blockAt(x: Int, y: Int, z: Int): String { seen.add(Triple(x, y, z)); return "minecraft:stone" }
            override fun findSlot(item: String) = -1
            override fun itemInSlot(slot: Int) = ""
            override fun pick(items: List<String>) = false
            override fun trace(distance: Int) = ""
        }
        val reg = VariableRegistry().apply {
            addEnvProvider { name ->
                when (name.uppercase()) {
                    "XPOS" -> Value.Num(100); "YPOS" -> Value.Num(64); "ZPOS" -> Value.Num(-40); else -> null
                }
            }
        }
        ScriptHost().run(
            "\$\${ getid(~, ~-1, ~2); getid(5, ~, ~1) }\$\$",
            registry = reg, client = object : ClientBridge { override val query get() = q },
        )
        assertEquals(Triple(100, 63, -38), seen[0]) // ~ = XPOS, ~-1 = YPOS-1, ~2 = ZPOS+2
        assertEquals(Triple(5, 64, -39), seen[1])   // 5 absolute, ~ = YPOS, ~1 = ZPOS+1
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
