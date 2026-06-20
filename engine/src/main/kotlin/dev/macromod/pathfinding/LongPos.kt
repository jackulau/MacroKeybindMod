package dev.macromod.pathfinding

/**
 * Packs a block position (x, y, z) into a single [Long] using Minecraft's `BlockPos.asLong` layout —
 * x in the high 26 bits, z in the middle 26, y in the low 12, all signed two's-complement. This lets
 * the A* search key its working sets on a primitive long instead of allocating and hashing a [Vec3i]
 * per visited node.
 *
 * Lossless for every position in the MC world range (x, z in -2^25..2^25-1; y in -2048..2047). The
 * pathfinder's `maxNodes` cap keeps searches local, well inside that. Coords outside the range would
 * wrap — the same constraint vanilla `BlockPos.asLong` carries, and unreachable for real pathfinding.
 */
internal object LongPos {
    private const val X_BITS = 26
    private const val Z_BITS = 26
    private const val Y_BITS = 12
    private const val Z_SHIFT = Y_BITS              // 12
    private const val X_SHIFT = Y_BITS + Z_BITS     // 38
    private const val X_MASK = (1L shl X_BITS) - 1L
    private const val Z_MASK = (1L shl Z_BITS) - 1L
    private const val Y_MASK = (1L shl Y_BITS) - 1L

    fun pack(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and X_MASK) shl X_SHIFT) or
            ((z.toLong() and Z_MASK) shl Z_SHIFT) or
            (y.toLong() and Y_MASK)

    fun pack(p: Vec3i): Long = pack(p.x, p.y, p.z)

    // Each accessor shifts the field up to the sign bit, then arithmetic-shifts back to sign-extend.
    fun x(packed: Long): Int = (packed shl (64 - X_SHIFT - X_BITS) shr (64 - X_BITS)).toInt()
    fun y(packed: Long): Int = (packed shl (64 - Y_BITS) shr (64 - Y_BITS)).toInt()
    fun z(packed: Long): Int = (packed shl (64 - Z_SHIFT - Z_BITS) shr (64 - Z_BITS)).toInt()

    fun unpack(packed: Long): Vec3i = Vec3i(x(packed), y(packed), z(packed))
}
