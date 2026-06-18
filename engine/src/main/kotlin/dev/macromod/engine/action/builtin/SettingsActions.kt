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
