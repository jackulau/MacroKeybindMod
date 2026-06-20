package dev.macromod.engine.event

/**
 * Pure decision logic for the durability change-watchers, extracted from the Fabric host so it can
 * be unit-tested headless. Both predicates encode MKB's rule: a durability event reflects WEAR, not
 * item SUBSTITUTION, so swapping the held item (or equipping/unequipping armour) must re-baseline
 * silently instead of firing. See MacroEventDispatcherBuiltin (held: lines 96-106, isSameItem gate +
 * suppressNext on change; armour: 144-157, per-slot suppressNext on empty-state flip).
 */
object DurabilityWatch {
    /**
     * onItemDurabilityChange: fire iff the held item is the SAME (by registry id) as last tick and
     * its durability changed. A null/blank prevId means this is the first sample (or the slot was
     * empty) -> treat as a different item, so we re-baseline without firing. Mirrors MKB's
     * `isSameItem && durabilityWatcher.checkValue(...)`.
     */
    fun heldDurabilityChanged(prevId: String?, curId: String, prevDur: Int, curDur: Int): Boolean =
        prevId != null && prevId == curId && prevDur != curDur

    /**
     * onArmourDurabilityChange: fire iff some armour slot was WORN both this tick and last
     * (prev[i] >= 0 && cur[i] >= 0) and its durability changed. A slot encoded as -1 is empty, so an
     * equip (-1 -> n) or unequip (n -> -1) is excluded -> the empty-state flip is suppressed and only
     * re-baselines. A worn->worn swap with a different durability still fires, matching MKB (which
     * suppresses only empty-state flips, not swaps). Indices are HEAD/CHEST/LEGS/FEET; the arrays are
     * compared position-wise up to the shorter length.
     */
    fun armourDurabilityChanged(prev: IntArray, cur: IntArray): Boolean {
        val n = minOf(prev.size, cur.size)
        for (i in 0 until n) {
            if (prev[i] >= 0 && cur[i] >= 0 && prev[i] != cur[i]) return true
        }
        return false
    }
}
