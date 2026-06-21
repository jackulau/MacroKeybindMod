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
        // The host WorldQuery speaks raw 0-based MC indices: the diamond sits at 0-based index 3 (scannable
        // only from a start <= 3), and only the raw first slot (0) holds the dirt stack.
        override fun findSlot(item: String) = if (item == "minecraft:diamond") 3 else -1
        override fun findSlot(item: String, startSlot: Int) = if (item == "minecraft:diamond" && startSlot <= 3) 3 else -1
        override fun itemInSlot(slot: Int) = "minecraft:dirt"
        override fun slotItem(slot: Int) = if (slot == 0) SlotItem("minecraft:dirt", 42, 7) else SlotItem.EMPTY
        override fun pick(items: List<String>) = "minecraft:sword" in items
        override fun trace(distance: Int) = "minecraft:grass_block"
    }
    private class Bridge(private val q: FakeQuery) : ClientBridge { override val query get() = q }

    private fun runQ(body: String): VariableRegistry {
        val reg = VariableRegistry()
        ScriptHost().run("\$\${ $body }\$\$", registry = reg, client = Bridge(FakeQuery()))
        return reg
    }

    @Test fun `getslot exposes the found slot 1-based`() {
        // The host finds the diamond at raw 0-based index 3; getslot exposes it 1-based as 4, so the natural
        // round-trip getslot(item,#s); slot(%s%) selects the right hotbar slot (slot()/%INVSLOT% are 1-based).
        assertEquals(4, runQ("getslot(\"minecraft:diamond\", #s)").getVariable("#s")!!.asInt())
    }

    @Test fun `getslot start slot is 1-based`() {
        // MKB GETSLOT(item,&out,start) scans from start (ScriptActionGetSlot.findItem:45-50); the start is the
        // same 1-based slot space as the result. start 4 (1-based) scans from raw 0-based 3 -> finds it -> 4.
        assertEquals(4, runQ("getslot(\"minecraft:diamond\", #s, 4)").getVariable("#s")!!.asInt())
        // start 5 (1-based) scans from raw 0-based 4 -> past the diamond at raw 3 -> not found (-1).
        assertEquals(-1, runQ("getslot(\"minecraft:diamond\", #s, 5)").getVariable("#s")!!.asInt())
    }

    @Test fun `getslotitem reads the 1-based slot`() {
        // MKB GETSLOTITEM(<slotid>,<#idvar>,...) writes the id into the out-var (ScriptActionGetSlotItem.java:36-37);
        // the slot is 1-based, so getslotitem(1) reads the raw 0-based first slot (matches %INVSLOT%==1).
        assertEquals("minecraft:dirt", runQ("getslotitem(1, &it)").getVariable("&it")!!.asString())
    }

    @Test fun `getslotitem writes stack count and damage to the out-vars`() {
        // MKB GETSLOTITEM(<slot>,<#id>,<#stack>,<#data>) also writes count=slotStack.E() + damage=slotStack.j()
        // (ScriptActionGetSlotItem.java:40-45). One slot fetch feeds all three out-vars.
        val reg = runQ("getslotitem(1, &id, #n, #d)")
        assertEquals("minecraft:dirt", reg.getVariable("&id")!!.asString())
        assertEquals(42, reg.getVariable("#n")!!.asInt())
        assertEquals(7, reg.getVariable("#d")!!.asInt())
    }

    @Test fun `getslotitem below 1 yields an empty stack`() {
        // MKB getSlotStack returns empty outside the 1..9 hotbar range; a slot below 1 has no stack.
        val reg = runQ("getslotitem(0, &id, #n, #d)")
        assertEquals("", reg.getVariable("&id")!!.asString())
        assertEquals(0, reg.getVariable("#n")!!.asInt())
        assertEquals(0, reg.getVariable("#d")!!.asInt())
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

    @Test fun `getidrel adds its offsets to the player position`() {
        // Unlike getid (where coords are absolute unless tilde-prefixed), getidrel is ALWAYS player-relative:
        // each arg is added to XPOS/YPOS/ZPOS. The id is both returned (capturable) and written to the
        // optional 4th out-var.
        val seen = mutableListOf<Triple<Int, Int, Int>>()
        val q = object : WorldQuery {
            override fun blockAt(x: Int, y: Int, z: Int): String { seen.add(Triple(x, y, z)); return "minecraft:diamond_ore" }
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
            "\$\${ &b = getidrel(1, -1, 2, &out) }\$\$",
            registry = reg, client = object : ClientBridge { override val query get() = q },
        )
        assertEquals(Triple(101, 63, -38), seen[0])                                 // XPOS+1, YPOS-1, ZPOS+2
        assertEquals("minecraft:diamond_ore", reg.getVariable("&b")!!.asString())   // captured return
        assertEquals("minecraft:diamond_ore", reg.getVariable("&out")!!.asString()) // 4th-arg out-var
    }

    @Test fun `getiteminfo reads the held item id`() {
        // getiteminfo queries the held item (slot -1 by convention); the fake returns the held stack's id.
        assertEquals("minecraft:dirt", runQ("&i = getiteminfo()").getVariable("&i")!!.asString())
    }
}
