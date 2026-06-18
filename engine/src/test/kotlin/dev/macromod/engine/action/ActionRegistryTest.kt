package dev.macromod.engine.action

import dev.macromod.engine.defaultActionRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the exact set of actions the default registry ships. This is the single source of
 * truth for "what we implement" — if you add or remove a built-in action, update [EXPECTED]
 * here (and the parity docs), and the diff this test prints tells you precisely what changed.
 * It stops the documented parity status from silently drifting out of sync with the code.
 */
class ActionRegistryTest {

    private val EXPECTED = setOf(
        // control flow
        "if", "elseif", "else", "endif", "do", "loop", "while", "until",
        "for", "foreach", "next", "break", "unsafe", "endunsafe",
        // string conditionals (if-family)
        "ifcontains", "ifbeginswith", "ifendswith", "ifmatches",
        // output / messaging
        "log", "echo", "sendmessage", "iif", "lograw", "logto", "clearchat", "selectchannel",
        // variables / arrays
        "set", "assign", "inc", "dec", "unset", "toggle",
        "push", "pop", "put", "arraysize", "indexof",
        // strings
        "lcase", "ucase", "length", "replace", "regexreplace", "match",
        "substr", "trim", "join", "split", "strip", "encode", "decode",
        // math
        "calc", "abs", "min", "max", "random", "sqrt",
        // date/time
        "time",
        // flow / task
        "pass", "stop",
        // input (route to InputController)
        "key", "keydown", "keyup", "press", "look", "turn", "sprint", "unsprint",
        "slot", "inventoryup", "inventorydown", "type", "togglekey",
        // navigation
        "goto", "stopnav", "calcyawto",
        // settings / options
        "fov", "gamma", "sensitivity", "music", "volume", "fog", "camera", "setres", "bind",
        "reloadresources", "shadergroup", "resourcepacks",
        "chatheight", "chatheightfocused", "chatwidth", "chatscale", "chatopacity", "chatvisible",
    )

    @Test fun `the default registry contains exactly the expected actions`() {
        val actual = defaultActionRegistry().names()
        val missing = EXPECTED - actual
        val unexpected = actual - EXPECTED
        assertTrue(
            missing.isEmpty() && unexpected.isEmpty(),
            "registry drift — missing: ${missing.sorted()} ; unexpected: ${unexpected.sorted()}",
        )
    }

    @Test fun `the implemented-action count is pinned`() {
        // 93 keywords. Bump this (and the parity docs) deliberately when adding an action.
        assertEquals(93, defaultActionRegistry().names().size)
    }

    @Test fun `every newly added action is registered`() {
        val names = defaultActionRegistry().names()
        for (n in listOf("strip", "encode", "decode", "sqrt", "time", "sprint", "unsprint")) {
            assertTrue(n in names, "expected '$n' to be registered")
        }
    }
}
