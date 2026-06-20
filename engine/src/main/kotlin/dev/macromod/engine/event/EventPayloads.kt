package dev.macromod.engine.event

/**
 * Pure helpers for deriving the payload of polled client events from before/after snapshots, so the
 * Fabric host can populate event-context variables (e.g. %PICKUPID%, %JOINEDPLAYER%) without any
 * Minecraft type leaking into the engine. Unit-tested headless; the host supplies the snapshots and
 * does the Minecraft-specific lookups (item name, damage) for the id this returns.
 */
object EventPayloads {
    /**
     * The item picked up between two inventory snapshots, each mapping registry id to the total count
     * held. Returns the id with the largest count increase paired with that increase, or null if
     * nothing rose (a drop, or no change). Ties resolve to the first such id encountered.
     *
     * Client polling cannot distinguish a pickup from a craft result or an inventory move, so this
     * describes whatever item count rose -- the same limitation the old total-count detector had,
     * now carrying the item identity instead of firing blind.
     */
    fun pickupDelta(prev: Map<String, Int>, cur: Map<String, Int>): Pair<String, Int>? {
        var bestId: String? = null
        var bestDelta = 0
        for ((id, count) in cur) {
            val delta = count - (prev[id] ?: 0)
            if (delta > bestDelta) {
                bestDelta = delta
                bestId = id
            }
        }
        return bestId?.let { it to bestDelta }
    }

    /**
     * The first player name present in [cur] but absent from [prev] (a newly joined player), or null
     * if no name was added. Keying on an added name rather than a population rise also catches a
     * simultaneous join+leave that leaves the head-count unchanged.
     */
    fun newJoiner(prev: Set<String>, cur: Set<String>): String? = cur.firstOrNull { it !in prev }
}
