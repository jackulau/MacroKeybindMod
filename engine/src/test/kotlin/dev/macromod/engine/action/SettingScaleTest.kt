package dev.macromod.engine.action

import dev.macromod.engine.action.builtin.SettingScale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The human <-> internal scaling for gamma/sensitivity, ported from MKB ScriptActionGamma.
 * gamma maps the brightness % (clamped 0-200) by /100; sensitivity maps 0-200 by /200; fov and any
 * un-tabled option pass through unchanged (MKB noScale).
 */
class SettingScaleTest {
    private val eps = 1e-9

    @Test fun `gamma human to internal divides by 100, clamped to 0-200`() {
        assertEquals(0.0, SettingScale.toInternal("gamma", 0.0), eps)
        assertEquals(0.5, SettingScale.toInternal("gamma", 50.0), eps)
        assertEquals(1.0, SettingScale.toInternal("gamma", 100.0), eps)
        assertEquals(2.0, SettingScale.toInternal("gamma", 200.0), eps)
        assertEquals(2.0, SettingScale.toInternal("gamma", 300.0), eps) // clamps to 200 -> /100
        assertEquals(0.0, SettingScale.toInternal("gamma", -10.0), eps) // clamps to 0
    }

    @Test fun `sensitivity human to internal divides by 200`() {
        assertEquals(0.0, SettingScale.toInternal("sensitivity", 0.0), eps)
        assertEquals(0.5, SettingScale.toInternal("sensitivity", 100.0), eps) // MC "100%" default
        assertEquals(1.0, SettingScale.toInternal("sensitivity", 200.0), eps)
    }

    @Test fun `internal to human inverts the scale`() {
        assertEquals(50.0, SettingScale.toHuman("gamma", 0.5), eps)
        assertEquals(100.0, SettingScale.toHuman("gamma", 1.0), eps)
        assertEquals(100.0, SettingScale.toHuman("sensitivity", 0.5), eps)
        assertEquals(200.0, SettingScale.toHuman("sensitivity", 1.0), eps)
    }

    @Test fun `round trips exactly within range`() {
        for (g in listOf(0.0, 25.0, 50.0, 100.0, 200.0))
            assertEquals(g, SettingScale.toHuman("gamma", SettingScale.toInternal("gamma", g)), eps)
        for (s in listOf(0.0, 50.0, 100.0, 200.0))
            assertEquals(s, SettingScale.toHuman("sensitivity", SettingScale.toInternal("sensitivity", s)), eps)
    }

    @Test fun `fov and untabled options pass through unchanged`() {
        assertEquals(70.0, SettingScale.toInternal("fov", 70.0), eps) // MKB noScale: raw degrees
        assertEquals(70.0, SettingScale.toHuman("fov", 70.0), eps)
        assertEquals(50.0, SettingScale.toInternal("music", 50.0), eps) // not in the scale table
    }

    @Test fun `option name is case insensitive`() {
        assertEquals(0.5, SettingScale.toInternal("GAMMA", 50.0), eps)
        assertEquals(100.0, SettingScale.toHuman("Sensitivity", 0.5), eps)
    }
}
