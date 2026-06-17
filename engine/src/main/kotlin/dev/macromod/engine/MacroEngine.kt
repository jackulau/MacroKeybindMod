package dev.macromod.engine

/**
 * MacroMod scripting engine — platform-agnostic core.
 *
 * A clean-room reimplementation of the scripting engine from the original
 * Macro/Keybind Mod (Mumfrey, `net.eq2online.macros`), decoupled from Minecraft
 * so the lexer, parser, runtime VM, variable system and expression evaluator are
 * all unit-testable on the plain JVM. Minecraft-specific behaviour is supplied by
 * the host (the Fabric module) through platform interfaces.
 */
object MacroEngine {
    const val VERSION = "0.1.0"
}
