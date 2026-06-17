package dev.macromod.fabric

import dev.macromod.engine.ScriptHost
import dev.macromod.engine.action.OutputSink
import net.fabricmc.api.ClientModInitializer
// Logging facade differs by era: Fabric re-exposes SLF4J only from 1.19+. For 1.16.5 /
// 1.17.1 / 1.18.2 there is no guaranteed SLF4J on the classpath, so fall back to Log4j2,
// which Minecraft has always bundled. Stonecutter swaps the active branch per version; the
// `logger.info("..", arg)` call sites stay identical because both APIs accept `{}`
// placeholders. Source-of-truth is the active version (1.21.1, >=1.19) → SLF4J branch live.
//? if >=1.19 {
import org.slf4j.LoggerFactory
//?} else
/*import org.apache.logging.log4j.LogManager*/

/**
 * Client entry point. Proves two things at load time:
 *  1. The mod is wired into Fabric (ClientModInitializer fires, logs a line).
 *  2. The pure-JVM `:engine` is shaded in and usable — we build a [ScriptHost],
 *     run a one-line script island, and forward its output to the mod logger.
 */
class MacroModClient : ClientModInitializer {
    //? if >=1.19 {
    private val logger = LoggerFactory.getLogger("MacroMod")
    //?} else
    /*private val logger = LogManager.getLogger("MacroMod")*/

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
