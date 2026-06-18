# MacroMod

A modern, multi-version rewrite of **The Macro / Keybind Mod** — the long-running
client-side Minecraft mod that binds keys, mouse buttons and menus to *scripts*:
from a one-liner that types a command to a full automation routine with variables,
loops, conditionals, events and pathfinding.

The original (`net.eq2online.macros`, by Mumfrey) froze at **Minecraft 1.12.2** on the
now-defunct LiteLoader. MacroMod revives it from the ground up on **Fabric**, with a
clean-room scripting engine and first-class **multi-version** support.

<div class="grid cards" markdown>

-   :material-vector-square:{ .lg } &nbsp; **One engine, every version**

    ---

    A pure-JVM scripting core with zero Minecraft dependencies, wired into Fabric and
    compiled against **23 Minecraft versions** (1.14.4 → 1.21.11) from a single source tree.

-   :material-code-braces:{ .lg } &nbsp; **Two languages, one runtime**

    ---

    Write the classic `$${ … }$$` MKB syntax **or** a clean modern brace syntax — both
    compile to the same instruction set.

-   :material-map-marker-path:{ .lg } &nbsp; **Its own pathfinding**

    ---

    A from-scratch A\* over the block grid (walk / diagonal / step / fall / parkour) — no
    Baritone. Drive it straight from a script with `goto(x, y, z)`.

-   :material-flask-outline:{ .lg } &nbsp; **Tested**

    ---

    The engine ships a real unit-test suite (130 tests): lexer, parser, runtime VM,
    variables, expressions, actions, pathfinding.

</div>

!!! tip "Try it in your browser"
    The **[Script Editor](editor.md)** runs MacroMod scripts live — write a macro, press
    **Run**, see the output. No install needed.

---

## At a glance

=== "Legacy MKB syntax"

    ```macro
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

    ```macro
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

Both compile to the **same** flat instruction list and run on the **same** virtual machine.
See [The DSL Language](guide/dsl-language.md).

---

## How it fits together

```text
            ┌────────────────────────────────────────────┐
            │  :engine  (pure JVM Kotlin — no Minecraft)   │
            │  lexer → compiler → AST → interpreter VM     │
            │  variables · expressions · actions · params  │
            │  pathfinding (A*) · macro model · modules    │
            └───────────────────────┬────────────────────┘
                                    │ shaded in (JIJ)
            ┌───────────────────────┴────────────────────┐
            │  :fabric  (Stonecutter, 23 MC versions)      │
            │  keybinds · MC actions · GUI · events · nav  │
            └─────────────────────────────────────────────┘
```

The engine is independently buildable and testable — the heart of the project never needs
Minecraft to develop or verify. See [Architecture](guide/architecture.md).

---

## Start here

<div class="grid cards" markdown>

- :material-rocket-launch: **[Getting Started](guide/getting-started.md)** — install, bind a key, write your first macro.
- :material-book-open-variant: **[The DSL Language](guide/dsl-language.md)** — variables, expressions, control flow, both syntaxes.
- :material-variable: **[Parameters & Variables](guide/parameters.md)** — `$$` codes and `%var%` expansion.
- :material-map-marker-path: **[Pathfinding](guide/pathfinding.md)** — the A\* engine and `goto`.
- :material-pencil-box-outline: **[Script Editor](editor.md)** — try scripts live in the browser.
- :material-format-list-bulleted: **[Catalog](catalog/PARITY.md)** — every action, variable and event.

</div>

---

!!! warning "Use responsibly"
    Automating gameplay can violate a server's rules (for example, Hypixel's Terms of
    Service). MacroMod is a general-purpose client tool; how you use it — and any
    consequences — are your responsibility.
