//? if >=1.16 {
package dev.macromod.fabric

import dev.macromod.engine.action.WorldQuery
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.BlockHitResult
// The block/item registries moved to BuiltInRegistries at 1.19.3 (was the static Registry.*).
// Source-of-truth 1.21.1 (>=1.19.3) uses BuiltInRegistries; the older branch is commented.
//? if >=1.19.3 {
import net.minecraft.core.registries.BuiltInRegistries
//?} else
/*import net.minecraft.core.Registry*/

/**
 * Live [WorldQuery]: reads the real client world + inventory and returns modern registry ids
 * (e.g. "minecraft:stone"). Gated for the registry-API move (BuiltInRegistries @1.19.3) and the
 * hotbar selected-slot accessor (private @1.21.5). Compile-verified across every supported
 * version; its in-game read behaviour needs a live client (not headless-testable).
 */
class FabricWorldQuery : WorldQuery {

    private fun blockId(pos: BlockPos): String {
        val level = Minecraft.getInstance().level ?: return ""
        val block = level.getBlockState(pos).block
        //? if >=1.19.3 {
        return BuiltInRegistries.BLOCK.getKey(block).toString()
        //?}
        //? if <1.19.3 {
        /*return Registry.BLOCK.getKey(block).toString()*/
        //?}
    }

    private fun itemId(stack: net.minecraft.world.item.ItemStack): String {
        if (stack.isEmpty) return ""
        //? if >=1.19.3 {
        return BuiltInRegistries.ITEM.getKey(stack.item).toString()
        //?}
        //? if <1.19.3 {
        /*return Registry.ITEM.getKey(stack.item).toString()*/
        //?}
    }

    /** True if a registry id matches the user's term ("minecraft:diamond", "diamond", or ":diamond"). */
    private fun matches(id: String, term: String): Boolean =
        id == term || id.substringAfter(':') == term.substringAfter(':')

    override fun blockAt(x: Int, y: Int, z: Int): String = blockId(BlockPos(x, y, z))

    override fun itemInSlot(slot: Int): String {
        val player = Minecraft.getInstance().player ?: return ""
        val stack = if (slot < 0) player.mainHandItem else player.inventory.getItem(slot)
        return itemId(stack)
    }

    override fun findSlot(item: String): Int {
        val inv = Minecraft.getInstance().player?.inventory ?: return -1
        for (i in 0 until inv.containerSize) {
            val s = inv.getItem(i)
            if (!s.isEmpty && matches(itemId(s), item.trim())) return i
        }
        return -1
    }

    override fun pick(items: List<String>): Boolean {
        val inv = Minecraft.getInstance().player?.inventory ?: return false
        for (term in items) {
            for (slot in 0..8) {
                val s = inv.getItem(slot)
                if (!s.isEmpty && matches(itemId(s), term.trim())) {
                    //? if >=1.21.5 {
                    /*inv.setSelectedSlot(slot)*/
                    //?}
                    //? if <1.21.5 {
                    inv.selected = slot
                    //?}
                    return true
                }
            }
        }
        return false
    }

    override fun trace(distance: Int): String {
        val hit = Minecraft.getInstance().hitResult as? BlockHitResult ?: return ""
        return blockId(hit.blockPos)
    }
}
//?}
