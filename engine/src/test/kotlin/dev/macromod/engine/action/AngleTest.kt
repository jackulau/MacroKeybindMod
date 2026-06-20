package dev.macromod.engine.action

import dev.macromod.engine.action.builtin.Angle
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `Angle.wrap` is the shared `[0, 360)` normalisation ported from MKB VariableProviderPlayer.java:60-74
 * (`(int)(v % 360)` then `while (v < 0) v += 360`). It backs `calcyawto` and the host
 * `%YAW%`/`%PITCH%`/`%CARDINALYAW%` reads, so these cases pin every convention they rely on.
 */
class AngleTest {
    @Test fun `already in range is unchanged`() {
        assertEquals(0, Angle.wrap(0))
        assertEquals(90, Angle.wrap(90))
        assertEquals(359, Angle.wrap(359))
    }

    @Test fun `negatives wrap up into range`() {
        assertEquals(350, Angle.wrap(-10))   // raw yaw -10 -> MKB 350
        assertEquals(270, Angle.wrap(-90))   // pitch looking straight up -> MKB 270
        assertEquals(180, Angle.wrap(-180))  // CARDINALYAW of yaw 0 (yaw - 180)
    }

    @Test fun `values past a full turn wrap down`() {
        assertEquals(10, Angle.wrap(370))
        assertEquals(0, Angle.wrap(360))
        assertEquals(0, Angle.wrap(720))
        assertEquals(180, Angle.wrap(540))   // CARDINALYAW of yaw 0 via 0 + 540 ((x%360)+540)%360 == wrap(x-180)
    }

    @Test fun `cardinalyaw equivalence holds for representative yaws`() {
        // CARDINALYAW used to be ((yRot % 360) + 540) % 360; consolidated onto wrap(yRot - 180).
        for (yRot in listOf(0, 90, 180, 270, -10, 370, -185, 725)) {
            assertEquals(((yRot % 360) + 540) % 360, Angle.wrap(yRot - 180))
        }
    }

    @Test fun `wrap is idempotent`() {
        for (d in listOf(-721, -180, -1, 0, 1, 359, 360, 1000)) {
            assertEquals(Angle.wrap(d), Angle.wrap(Angle.wrap(d)))
        }
    }
}
