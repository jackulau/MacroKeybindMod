package dev.macromod.engine.event

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DurabilityWatchTest {
    // ---- held item ----
    @Test fun `held fires when the same item loses durability`() {
        assertTrue(DurabilityWatch.heldDurabilityChanged("minecraft:diamond_pickaxe", "minecraft:diamond_pickaxe", 1561, 1560))
    }

    @Test fun `held fires when the same item is repaired (durability rises)`() {
        // MKB checkValue fires on any change, not only a drop.
        assertTrue(DurabilityWatch.heldDurabilityChanged("minecraft:elytra", "minecraft:elytra", 100, 200))
    }

    @Test fun `held suppresses an item switch even when durability differs`() {
        // sword (100) -> pickaxe (40): different item, must NOT fire.
        assertFalse(DurabilityWatch.heldDurabilityChanged("minecraft:iron_sword", "minecraft:diamond_pickaxe", 100, 40))
    }

    @Test fun `held suppresses the first sample (null prevId)`() {
        assertFalse(DurabilityWatch.heldDurabilityChanged(null, "minecraft:shears", 0, 238))
    }

    @Test fun `held does not fire when nothing changed`() {
        assertFalse(DurabilityWatch.heldDurabilityChanged("minecraft:bow", "minecraft:bow", 384, 384))
    }

    // ---- armour (HEAD/CHEST/LEGS/FEET; -1 = empty) ----
    @Test fun `armour fires when a worn piece loses durability`() {
        assertTrue(DurabilityWatch.armourDurabilityChanged(intArrayOf(165, 240, 225, 195), intArrayOf(165, 239, 225, 195)))
    }

    @Test fun `armour suppresses equipping a piece into an empty slot`() {
        // FEET goes empty(-1) -> worn(195): an equip, not wear. Must NOT fire.
        assertFalse(DurabilityWatch.armourDurabilityChanged(intArrayOf(165, 240, 225, -1), intArrayOf(165, 240, 225, 195)))
    }

    @Test fun `armour suppresses unequipping a piece`() {
        // CHEST goes worn(240) -> empty(-1). Must NOT fire.
        assertFalse(DurabilityWatch.armourDurabilityChanged(intArrayOf(165, 240, 225, 195), intArrayOf(165, -1, 225, 195)))
    }

    @Test fun `armour fires on a worn-to-worn swap with different durability (matches MKB)`() {
        // CHEST swapped for another worn chestplate with different durability: empty-state unchanged -> fires.
        assertTrue(DurabilityWatch.armourDurabilityChanged(intArrayOf(165, 240, 225, 195), intArrayOf(165, 528, 225, 195)))
    }

    @Test fun `armour does not fire when nothing changed`() {
        assertFalse(DurabilityWatch.armourDurabilityChanged(intArrayOf(165, 240, 225, 195), intArrayOf(165, 240, 225, 195)))
    }

    @Test fun `armour does not fire when only empty slots are involved`() {
        assertFalse(DurabilityWatch.armourDurabilityChanged(intArrayOf(-1, -1, -1, -1), intArrayOf(-1, -1, -1, -1)))
    }
}
