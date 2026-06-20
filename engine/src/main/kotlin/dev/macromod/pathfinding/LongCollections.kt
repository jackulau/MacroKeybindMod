package dev.macromod.pathfinding

/**
 * Minimal primitive long-keyed collections for the A* working sets. The search only ever inserts and
 * updates (it never removes a node), so these need no tombstones: linear-probe open addressing with a
 * parallel `used` flag, doubling at a 2/3 load factor. Keys are [LongPos]-packed block positions, so
 * they carry their entropy in the high/mid bits; [mix] (Fibonacci hashing) scatters them across the
 * table instead of clustering on the low y bits. Together they remove the autoboxing a
 * `HashMap<Long, *>` / `HashSet<Long>` would incur per visited node.
 */
private fun tableSize(capacity: Int): Int {
    var n = 1
    while (n < capacity) n = n shl 1
    return maxOf(n, 4)
}

private fun mix(key: Long): Int {
    var h = key * -0x61c8864680b583ebL // 2^64 / golden ratio
    h = h xor (h ushr 32)
    return h.toInt()
}

/** Open-addressing `long -> double` map (A* g-scores). Insert/update only. */
internal class LongDoubleMap(initialCapacity: Int = 256) {
    private var keys = LongArray(tableSize(initialCapacity))
    private var vals = DoubleArray(keys.size)
    private var used = BooleanArray(keys.size)
    private var mask = keys.size - 1
    private var count = 0

    fun get(key: Long, default: Double): Double {
        var i = mix(key) and mask
        while (used[i]) {
            if (keys[i] == key) return vals[i]
            i = (i + 1) and mask
        }
        return default
    }

    fun put(key: Long, value: Double) {
        var i = mix(key) and mask
        while (used[i]) {
            if (keys[i] == key) { vals[i] = value; return }
            i = (i + 1) and mask
        }
        used[i] = true; keys[i] = key; vals[i] = value; count++
        if (count * 3 >= keys.size * 2) grow()
    }

    private fun grow() {
        val ok = keys; val ov = vals; val ou = used
        keys = LongArray(ok.size * 2); vals = DoubleArray(keys.size); used = BooleanArray(keys.size)
        mask = keys.size - 1; count = 0
        for (j in ok.indices) if (ou[j]) put(ok[j], ov[j])
    }
}

/** Open-addressing `long -> long` map (A* cameFrom parent pointers). Insert/update only. */
internal class LongLongMap(initialCapacity: Int = 256) {
    private var keys = LongArray(tableSize(initialCapacity))
    private var vals = LongArray(keys.size)
    private var used = BooleanArray(keys.size)
    private var mask = keys.size - 1
    private var count = 0

    fun containsKey(key: Long): Boolean {
        var i = mix(key) and mask
        while (used[i]) {
            if (keys[i] == key) return true
            i = (i + 1) and mask
        }
        return false
    }

    fun get(key: Long): Long {
        var i = mix(key) and mask
        while (used[i]) {
            if (keys[i] == key) return vals[i]
            i = (i + 1) and mask
        }
        return 0L
    }

    fun put(key: Long, value: Long) {
        var i = mix(key) and mask
        while (used[i]) {
            if (keys[i] == key) { vals[i] = value; return }
            i = (i + 1) and mask
        }
        used[i] = true; keys[i] = key; vals[i] = value; count++
        if (count * 3 >= keys.size * 2) grow()
    }

    private fun grow() {
        val ok = keys; val ov = vals; val ou = used
        keys = LongArray(ok.size * 2); vals = LongArray(keys.size); used = BooleanArray(keys.size)
        mask = keys.size - 1; count = 0
        for (j in ok.indices) if (ou[j]) put(ok[j], ov[j])
    }
}

/** Open-addressing `long` set (A* closed set). Add/contains only. */
internal class LongSet(initialCapacity: Int = 256) {
    private var keys = LongArray(tableSize(initialCapacity))
    private var used = BooleanArray(keys.size)
    private var mask = keys.size - 1
    private var count = 0

    fun contains(key: Long): Boolean {
        var i = mix(key) and mask
        while (used[i]) {
            if (keys[i] == key) return true
            i = (i + 1) and mask
        }
        return false
    }

    /** Adds [key]; returns true if it was newly inserted, false if already present. */
    fun add(key: Long): Boolean {
        var i = mix(key) and mask
        while (used[i]) {
            if (keys[i] == key) return false
            i = (i + 1) and mask
        }
        used[i] = true; keys[i] = key; count++
        if (count * 3 >= keys.size * 2) grow()
        return true
    }

    private fun grow() {
        val ok = keys; val ou = used
        keys = LongArray(ok.size * 2); used = BooleanArray(keys.size); mask = keys.size - 1; count = 0
        for (j in ok.indices) if (ou[j]) add(ok[j])
    }
}
