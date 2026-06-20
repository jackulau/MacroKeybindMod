package dev.macromod.engine.action.builtin

import dev.macromod.engine.action.Args
import dev.macromod.engine.action.ExecutionContext
import dev.macromod.engine.action.ReturnValue
import dev.macromod.engine.action.ScriptAction

/**
 * A client-option action. Each keyword (`fov`, `gamma`, `volume`, …) is one of these; it hands
 * its (expanded) args to the platform [dev.macromod.engine.action.ClientSettings], which applies
 * the change (the Fabric host dispatches by name). Engine-side it is a thin, uniform router, so a
 * recording fake can verify the right (name, args) reached the platform without Minecraft.
 */
private class SettingAction(name: String) : ScriptAction(name) {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.client.settings.apply(name, (0 until args.size).map { ctx.expand(args[it]) })
        return ReturnValue.Void
    }
}

/** Settings / options actions, for bulk registration. */
val SETTINGS_ACTIONS: List<ScriptAction> = listOf(
    "fov", "gamma", "sensitivity", "music", "volume", "fog", "camera", "setres", "bind",
    "reloadresources", "shadergroup", "resourcepacks",
    "chatheight", "chatheightfocused", "chatwidth", "chatscale", "chatopacity", "chatvisible",
).map { SettingAction(it) }

/**
 * Human <-> internal value scaling for the analog client options, ported from MKB
 * `ScriptActionGamma.targetValue()` / `valueToTarget()`. Scripts speak the MKB human range (gamma
 * as a brightness %, sensitivity 0-200, both per ACTIONS.md); Minecraft stores each as an internal
 * `[0,1]` value. The settings actions write through [toInternal] and the `%GAMMA%`/`%SENSITIVITY%`
 * reads report through [toHuman], so a `gamma(%GAMMA%)` round-trip is exact. Options absent from
 * the table (e.g. `fov`, which MKB marks `noScale` — raw degrees) pass through unchanged.
 *
 * Lives in the engine (not the Fabric host) so the scaling logic is a single, headless-unit-tested
 * source of truth; the host is a thin caller of both directions.
 */
object SettingScale {
    // (min, max, scaleMin, scaleMax) mirror MKB ScriptActionGamma's minValue/maxValue/scaleMin/scaleMax.
    private data class Scale(val min: Double, val max: Double, val scaleMin: Double, val scaleMax: Double)
    private val SCALES = mapOf(
        "gamma" to Scale(0.0, 200.0, 0.0, 100.0),       // ScriptActionGamma: maxValue 200, scaleMax 100 -> /100
        "sensitivity" to Scale(0.0, 200.0, 0.0, 200.0), // ScriptActionSensitivity: 0..200 -> 0..1 -> /200
    )

    /** Human script value (gamma %, sensitivity 0-200) -> Minecraft internal `[0,1]` (clamped). */
    fun toInternal(name: String, human: Double): Double {
        val s = SCALES[name.lowercase()] ?: return human
        val clamped = human.coerceIn(s.min, s.max)
        return (clamped - s.scaleMin) / (s.scaleMax - s.scaleMin)
    }

    /** Minecraft internal `[0,1]` -> human script value; inverse of [toInternal] within range. */
    fun toHuman(name: String, internal: Double): Double {
        val s = SCALES[name.lowercase()] ?: return internal
        return internal * (s.scaleMax - s.scaleMin) + s.scaleMin
    }
}
