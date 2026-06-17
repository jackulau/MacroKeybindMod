package dev.macromod.fabric

import dev.macromod.engine.ScriptHost
import dev.macromod.engine.action.OutputSink
import net.fabricmc.api.ClientModInitializer
import org.slf4j.LoggerFactory

/**
 * Client entry point. Proves two things at load time:
 *  1. The mod is wired into Fabric (ClientModInitializer fires, logs a line).
 *  2. The pure-JVM `:engine` is shaded in and usable — we build a [ScriptHost],
 *     run a one-line script island, and forward its output to the mod logger.
 */
class MacroModClient : ClientModInitializer {
    private val logger = LoggerFactory.getLogger("MacroMod")

    override fun onInitializeClient() {
        logger.info("MacroMod client initializing")

        // Forward engine output (chat + log) into the mod logger.
        val sink = object : OutputSink {
            override fun chat(message: String) = logger.info("[chat] {}", message)
            override fun log(message: String) = logger.info("[log] {}", message)
        }

        // Compile + run a tiny macro: a `$${ ... }$$` script island calling the `log` action.
        val host = ScriptHost()
        host.run("\$\${ log(\"MacroMod engine ready\") }\$\$", sink)

        logger.info("MacroMod client ready")
    }
}
