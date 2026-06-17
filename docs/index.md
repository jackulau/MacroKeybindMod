# MacroMod

A modern, multi-version rewrite of **The Macro / Keybind Mod** — the long-running
client-side Minecraft mod that binds keys, mouse buttons and menus to *scripts*,
from a one-liner that types a command to a full automation routine with variables,
loops, conditionals and events.

The original (`net.eq2online.macros`, by Mumfrey) froze at **Minecraft 1.12.2** on
the now-defunct LiteLoader. MacroMod revives it from the ground up on **Fabric**,
with a clean-room scripting engine and first-class **multi-version** support.

!!! abstract "What makes this rewrite different"
    - **One engine, every version.** A pure-JVM scripting core with zero Minecraft
      dependencies, wired into Fabric through a thin adapter and compiled against
      **18 Minecraft versions** (1.19.2 → 1.21.11) from a single source tree via
      [Stonecutter](https://stonecutter.kikugie.dev/).
    - **Two languages, one runtime.** Write the classic `$${ … }$$` MKB syntax *or*
      a clean modern brace syntax — both compile to the same instruction set, so old
      scripts keep working and new ones read like real code.
    - **Tested.** The engine ships with a real unit-test suite (lexer, parser, runtime
      VM, variables, expressions, actions, parameter substitution).

---

## At a glance

=== "Legacy MKB syntax"

    ```text
    $${
      // toggle an auto-clicker
      if(automine);
        log("Automine off"); unset(automine);
      else;
        log("Automine on"); set(automine);
        do; key(attack); while(automine);
      endif;
    }$$
    ```

=== "Modern syntax"

    ```text
    if automine {
      log("Automine off")
      unset(automine)
    } else {
      log("Automine on")
      set(automine)
      forever {
        key(attack)
      }
    }
    ```

Both compile to the **same** flat instruction list and run on the **same** virtual
machine. See [The DSL Language](guide/dsl-language.md).

---

## How it fits together

```
            ┌────────────────────────────────────────────┐
            │  :engine  (pure JVM Kotlin — no Minecraft)   │
            │  lexer → compiler → AST → interpreter VM     │
            │  variables · expressions · actions · params  │
            └───────────────────────┬────────────────────┘
                                    │ shaded in (JIJ)
            ┌───────────────────────┴────────────────────┐
            │  :fabric  (Stonecutter, 18 MC versions)      │
            │  keybinds · MC-bound actions · GUI · events  │
            └─────────────────────────────────────────────┘
```

The engine is independently buildable and testable — the heart of the project never
needs Minecraft to develop or verify. See [Architecture](guide/architecture.md).

---

## Version support

MacroMod builds for **every Minecraft 1.21.x release** (1.21 → 1.21.11, including the
Hypixel SkyBlock floor of 1.21.9+) and back through **1.20.x and 1.19.x** — 18 versions
in total, each producing its own remapped mod jar. The full matrix, with the exact
Fabric API / Java toolchain per version, lives in [Versions](VERSIONS.md).

---

## Where to go next

<div class="grid cards" markdown>

- :material-rocket-launch: **[Getting Started](guide/getting-started.md)** — install the
  mod, bind a key, write your first macro.
- :material-code-braces: **[The DSL Language](guide/dsl-language.md)** — the complete
  language guide: variables, expressions, control flow, both syntaxes.
- :material-variable: **[Parameters & Variables](guide/parameters.md)** — `$$` codes and
  `%var%` expansion explained.
- :material-cogs: **[Architecture](guide/architecture.md)** — the engine internals and
  the multi-version build.

</div>

!!! warning "Use responsibly"
    Automating gameplay can violate a server's rules (for example, Hypixel's Terms of
    Service). MacroMod is a general-purpose client tool; how you use it — and any
    consequences — are your responsibility.
