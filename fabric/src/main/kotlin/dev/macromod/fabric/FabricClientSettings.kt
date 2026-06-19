//? if >=1.16 {
package dev.macromod.fabric

import dev.macromod.engine.action.ClientSettings
import net.minecraft.client.Minecraft
import net.minecraft.client.Options

/**
 * Live client-option mutation for the engine's settings actions (`fov` / `gamma` / `sensitivity` / ...).
 *
 * Modern MC (>=1.19) exposes the video options as `OptionInstance` accessors — `options.fov().set(v)`;
 * pre-1.19 they are plain public fields — `options.fov = v`. Both paths write the same underlying
 * value; the Stonecutter gate at 1.19 selects the right one per version. Options without a single
 * cross-version-stable mutation path (sound volumes via `SoundSource`, fog / camera / resolution /
 * key binds) fall back to visible [feedback] so the keyword still does something observable on every
 * supported version rather than being silently dropped.
 */
class FabricClientSettings(private val feedback: (String) -> Unit) : ClientSettings {
    override fun apply(name: String, args: List<String>) {
        val options = Minecraft.getInstance().options
        val value = args.firstOrNull()?.toDoubleOrNull()
        when (name.lowercase()) {
            "fov" -> if (value != null) setFov(options, value.toInt()) else feedback("[fov] expects a number")
            "gamma" -> if (value != null) setGamma(options, value) else feedback("[gamma] expects a number")
            "sensitivity" -> if (value != null) setSensitivity(options, value) else feedback("[sensitivity] expects a number")
            "renderdistance" -> if (value != null) setRenderDistance(options, value.toInt()) else feedback("[renderdistance] expects a number")
            else -> feedback("[setting] $name ${args.joinToString(" ")}".trim())
        }
    }

    private fun setFov(o: Options, v: Int) {
        //? if >=1.19 {
        o.fov().set(v)
        //?}
        //? if <1.19 {
        /*o.fov = v.toDouble()*/
        //?}
    }

    private fun setGamma(o: Options, v: Double) {
        //? if >=1.19 {
        o.gamma().set(v)
        //?}
        //? if <1.19 {
        /*o.gamma = v*/
        //?}
    }

    private fun setSensitivity(o: Options, v: Double) {
        //? if >=1.19 {
        o.sensitivity().set(v)
        //?}
        //? if <1.19 {
        /*o.sensitivity = v*/
        //?}
    }

    private fun setRenderDistance(o: Options, v: Int) {
        //? if >=1.19 {
        o.renderDistance().set(v)
        //?}
        //? if <1.19 {
        /*o.renderDistance = v*/
        //?}
    }
}
//?}
