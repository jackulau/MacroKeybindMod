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

    @Test fun `stop sigil truncates the script`() {
        assertEquals("keep ", ParamSubstitutor().process("keep \$\$! drop this"))
    }

    @Test fun `inline list defaults to the first item with no resolver`() {
        assertEquals("red", ParamSubstitutor().process("\$\$[[red,green,blue]]"))
    }

    @Test fun `inline list uses the resolver when present`() {
        val sub = ParamSubstitutor({ code, _ -> if (code == ParamCode.LIST) "green" else null })
        assertEquals("green", sub.process("\$\$[[red,green,blue]]"))
    }

    @Test fun `include splices a file via the resolver`() {
        val sub = ParamSubstitutor({ code, label -> if (code == ParamCode.INCLUDE && label == "greet.txt") "hello" else null })
        assertEquals("hello", sub.process("\$\$<greet.txt>"))
    }

    @Test fun `picker codes (k m p s) resolve`() {
        val sub = ParamSubstitutor({ code, _ -> if (code == ParamCode.SHADER) "fancy" else null })
        assertEquals("fancy", sub.process("\$\$s"))
    }
}
