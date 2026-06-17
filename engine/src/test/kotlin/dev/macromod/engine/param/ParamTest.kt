package dev.macromod.engine.param

import kotlin.test.Test
import kotlin.test.assertEquals

class ParamTest {
    @Test fun `preset positional codes substitute`() {
        assertEquals("say hi", ParamSubstitutor(presets = listOf("hi")).process("say \$\$0"))
    }

    @Test fun `named code resolves via the resolver`() {
        val sub = ParamSubstitutor({ code, label -> if (code == ParamCode.NAMED && label == "who") "bob" else null })
        assertEquals("hi bob", sub.process("hi \$\$[who]"))
    }

    @Test fun `simple interactive code resolves via the resolver`() {
        val sub = ParamSubstitutor({ code, _ -> if (code == ParamCode.FRIEND) "alice" else null })
        assertEquals("/msg alice", sub.process("/msg \$\$f"))
    }

    @Test fun `unresolved interactive code becomes empty`() {
        assertEquals("x", ParamSubstitutor().process("x\$\$?"))
    }

    @Test fun `escaped dollar-dollar is left literal`() {
        assertEquals("\$\$0", ParamSubstitutor(presets = listOf("X")).process("\\\$\$0"))
    }
}
