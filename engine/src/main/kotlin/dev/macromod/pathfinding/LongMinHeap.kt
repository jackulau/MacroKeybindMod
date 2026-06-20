package dev.macromod.pathfinding

/**
 * A primitive binary min-heap over parallel arrays: a [LongPos]-packed payload keyed by a `double`
 * priority. Replaces `PriorityQueue<Node>` in the A* open list so each push stores two primitives
 * into arrays instead of allocating a `Node` object (and the JDK heap's boxing `Object[]`),
 * leaving the search's inner loop free of per-node heap allocation. Ties in priority resolve by
 * heap structure (left child preferred), like any binary heap. Push + poll only — A* pushes a
 * fresh entry on each improvement and skips already-closed pops, so no decrease-key is needed.
 */
internal class LongMinHeap(initialCapacity: Int = 256) {
    private var keys = LongArray(maxOf(initialCapacity, 1))
    private var prio = DoubleArray(keys.size)
    private var size = 0

    val isEmpty: Boolean get() = size == 0

    fun push(key: Long, priority: Double) {
        if (size == keys.size) grow()
        var i = size
        // sift up: walk toward the root while the parent is strictly greater
        while (i > 0) {
            val parent = (i - 1) ushr 1
            if (prio[parent] <= priority) break
            keys[i] = keys[parent]; prio[i] = prio[parent]
            i = parent
        }
        keys[i] = key; prio[i] = priority
        size++
    }

    /** Removes and returns the packed key with the smallest priority. Caller must check [isEmpty] first. */
    fun poll(): Long {
        val top = keys[0]
        size--
        if (size > 0) {
            val key = keys[size]; val priority = prio[size]
            // sift down: settle the former last element from the root
            var i = 0
            val half = size ushr 1 // a node with no children once i >= half
            while (i < half) {
                var child = (i shl 1) + 1
                val right = child + 1
                if (right < size && prio[right] < prio[child]) child = right
                if (prio[child] >= priority) break
                keys[i] = keys[child]; prio[i] = prio[child]
                i = child
            }
            keys[i] = key; prio[i] = priority
        }
        return top
    }

    private fun grow() {
        keys = keys.copyOf(keys.size * 2)
        prio = prio.copyOf(keys.size)
    }
}
