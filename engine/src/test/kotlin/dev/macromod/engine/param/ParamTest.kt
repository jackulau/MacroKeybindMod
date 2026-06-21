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

    @Test fun `place sub-codes route PLACE with the component label`() {
        // MKB MacroParamProviderPlace `$$(px|py|pz|pn|p)`: x/y/z/place-name/formatted-coords.
        val sub = ParamSubstitutor({ code, label -> if (code == ParamCode.PLACE) "[${label ?: ""}]" else null })
        assertEquals("[x] [y] [z] [n] []", sub.process("\$\$px \$\$py \$\$pz \$\$pn \$\$p"))
    }

    @Test fun `place sub-codes are empty under no resolver (no stray suffix leak)`() {
        // The old single-char `p`-only regex left `$$px` -> "" + a stray literal "x"; now `px` matches whole.
        assertEquals("go ", ParamSubstitutor().process("go \$\$px\$\$py\$\$pz\$\$pn"))
    }

    @Test fun `combined item-damage routes ITEM with the colon-d label`() {
        // MKB MacroParamProviderItem `$$(d|i(:d)?)`: $$i -> id, $$d -> damage, $$i:d -> id:damage.
        val sub = ParamSubstitutor({ code, label ->
            when {
                code == ParamCode.ITEM && label == ":d" -> "stone:3"
                code == ParamCode.ITEM -> "stone"
                code == ParamCode.ITEM_DAMAGE -> "3"
                else -> null
            }
        })
        assertEquals("stone:3 | stone | 3", sub.process("\$\$i:d | \$\$i | \$\$d"))
    }

    @Test fun `combined item-damage is empty under no resolver (no colon-d leak)`() {
        // The old `i`-only regex left `$$i:d` -> "" + a stray literal ":d"; now `i:d` matches whole.
        assertEquals("give ", ParamSubstitutor().process("give \$\$i:d"))
    }

    @Test fun `escaped place sub-code stays literal`() {
        assertEquals("\$\$px", ParamSubstitutor().process("\\\$\$px"))
    }

    @Test fun `labeled inline list resolves without leaking`() {
        // MKB MacroParamProviderList `$$[label[opts]hint]` — the labeled form, not just `$$[[..]]`.
        // Before the regex widening this whole token stayed literal (LIST needed `$$[[`).
        assertEquals("red", ParamSubstitutor().process("\$\$[pick[red,green,blue]]"))
    }

    @Test fun `labeled inline list routes options to the resolver`() {
        val sub = ParamSubstitutor({ code, label -> if (code == ParamCode.LIST) "[$label]" else null })
        assertEquals("[red,green,blue]", sub.process("\$\$[pick[red,green,blue]]"))
    }

    @Test fun `inline list with a type hint is recognized`() {
        // hint `i:d` (item list incl. damage) — recognized + resolved to the first option, not left literal.
        assertEquals("a", ParamSubstitutor().process("\$\$[lbl[a,b]i:d]"))
    }
}
