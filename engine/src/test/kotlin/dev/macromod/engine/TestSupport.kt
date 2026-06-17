package dev.macromod.engine

import dev.macromod.engine.action.OutputSink
import dev.macromod.engine.variable.VariableRegistry

/** Captures everything a script sends to chat / log so tests can assert on it. */
class RecordingOutput : OutputSink {
    val chats = mutableListOf<String>()
    val logs = mutableListOf<String>()
    override fun chat(message: String) { chats.add(message) }
    override fun log(message: String) { logs.add(message) }
}

/** Compile + run [source] in the bind format, returning captured output. */
fun runMacro(source: String, registry: VariableRegistry = VariableRegistry()): RecordingOutput {
    val out = RecordingOutput()
    ScriptHost().run(source, out, registry)
    return out
}

/** Wrap a bare script body in a `$${ … }$$` island and run it. */
fun runScript(body: String, registry: VariableRegistry = VariableRegistry()): RecordingOutput =
    runMacro("\$\${ $body }\$\$", registry)
