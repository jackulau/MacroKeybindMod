# MacroKeybindMod

A modern, multi-version rewrite of **The Macro / Keybind Mod** — the long-running
client-side Minecraft mod that binds keys, mouse buttons and menus to *scripts*:
from a one-liner that types a command to a full automation routine with variables,
loops, conditionals, events and pathfinding.

The original (`net.eq2online.macros`, by Mumfrey) froze at **Minecraft 1.12.2** on the
now-defunct LiteLoader. MacroKeybindMod revives it from the ground up on **Fabric**, with
first-class **multi-version** support.

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

    The engine ships a real unit-test suite (357 tests): lexer, parser, runtime VM,
    variables, expressions, actions, pathfinding.

</div>

!!! tip "Try it in your browser"
    The **[Script Editor](editor.md)** runs MacroKeybindMod scripts live — write a macro, press
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

<figure class="mm-diagram" markdown="0">
<svg viewBox="0 0 720 330" role="img" xmlns="http://www.w3.org/2000/svg" fill="none" stroke="currentColor" aria-label="The :engine module (pure-JVM Kotlin) is shaded via Jar-in-Jar into the :fabric module (Stonecutter, 23 Minecraft versions).">
  <rect x="40" y="16" width="640" height="126" rx="12" stroke-width="2"/>
  <text x="64" y="50" stroke="none" fill="currentColor" font-family="ui-monospace,SFMono-Regular,Menlo,monospace" font-size="18" font-weight="700">:engine</text>
  <text x="156" y="50" stroke="none" fill="currentColor" font-family="ui-monospace,SFMono-Regular,Menlo,monospace" font-size="14" opacity="0.75">pure-JVM Kotlin · no Minecraft</text>
  <text x="64" y="82" stroke="none" fill="currentColor" font-family="ui-monospace,SFMono-Regular,Menlo,monospace" font-size="14.5">lexer → compiler → AST → interpreter VM</text>
  <text x="64" y="106" stroke="none" fill="currentColor" font-family="ui-monospace,SFMono-Regular,Menlo,monospace" font-size="14.5">variables · expressions · actions · params</text>
  <text x="64" y="128" stroke="none" fill="currentColor" font-family="ui-monospace,SFMono-Regular,Menlo,monospace" font-size="14.5">pathfinding (A*) · macro model · modules</text>
  <line x1="360" y1="142" x2="360" y2="186" stroke-width="2"/>
  <polygon points="360,194 353,180 367,180" fill="currentColor" stroke="none"/>
  <text x="376" y="172" stroke="none" fill="currentColor" font-family="ui-monospace,SFMono-Regular,Menlo,monospace" font-size="13" opacity="0.75">shaded in (Jar-in-Jar)</text>
  <rect x="40" y="200" width="640" height="110" rx="12" stroke-width="2"/>
  <text x="64" y="236" stroke="none" fill="currentColor" font-family="ui-monospace,SFMono-Regular,Menlo,monospace" font-size="18" font-weight="700">:fabric</text>
  <text x="150" y="236" stroke="none" fill="currentColor" font-family="ui-monospace,SFMono-Regular,Menlo,monospace" font-size="14" opacity="0.75">Stonecutter · 23 Minecraft versions</text>
  <text x="64" y="270" stroke="none" fill="currentColor" font-family="ui-monospace,SFMono-Regular,Menlo,monospace" font-size="14.5">keybinds · MC actions · GUI · events · navigation</text>
</svg>
</figure>

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
    Service). MacroKeybindMod is a general-purpose client tool; how you use it — and any
    consequences — are your responsibility.
