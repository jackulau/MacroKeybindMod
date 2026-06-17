# Getting Started

This page takes you from a clean Minecraft install to a working, key-bound macro.

!!! note "Status"
    MacroMod is under active development. The scripting **engine** is complete and
    tested; the **Fabric integration** currently builds for 18 Minecraft versions and
    loads as a client mod. The in-game keybind editor and the full Minecraft-bound
    action set (`key`, `look`, `craft`, player/world variables, events) are on the
    [roadmap](#roadmap). The script syntax below is final and runnable in the engine
    today.

## Requirements

| You need | Why |
| --- | --- |
| A supported Minecraft version | 1.19.2 – 1.21.11 — see the [version matrix](../VERSIONS.md) |
| [Fabric Loader](https://fabricmc.net/use/) | The mod loader MacroMod targets |
| [Fabric API](https://modrinth.com/mod/fabric-api) | Standard Fabric library, matched to your MC version |
| [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) | Runtime for the Kotlin entrypoint |
| Java | Bundled with the launcher; 17 for ≤1.20.4, 21 for 1.20.5+ |

## Install

1. Install **Fabric Loader** for your Minecraft version.
2. Drop these into `.minecraft/mods/`:
    - `macromod-<version>+<mc>.jar` (the engine is bundled inside — no separate download)
    - `fabric-api`
    - `fabric-language-kotlin`
3. Launch the Fabric profile. MacroMod logs a readiness line on start.

!!! tip "Which jar?"
    Each Minecraft version has its own jar (for example `macromod-0.1.0+1.21.9.jar`).
    Pick the one matching your client exactly. The list is in [Versions](../VERSIONS.md).

## Where scripts live

MacroMod follows the original mod's convention: standalone script files are plain
`.txt` files you can `$$<include>` or bind directly. A script is just text — the same
"chat text with `$${ … }$$` islands" format described in
[The DSL Language](dsl-language.md).

```text title="say_hi.txt"
$${ log("Hello from MacroMod"); }$$
```

## Your first macro

A macro is a piece of script bound to a trigger (a key, a mouse button, or an event).
Anything **outside** `$${ … }$$` is sent to the server as chat or a command; anything
**inside** is executed as script.

=== "Plain command"

    Bind this to a key to send a command with one press:

    ```text
    /home
    ```

=== "Command with a prompt"

    `$$?` opens a text field and substitutes what you type:

    ```text
    /msg $$? hello there
    ```

=== "A real script"

    Toggle sprint-attacking with a flag and a loop:

    ```text
    $${
      if(attacking);
        unset(attacking); log("stopped");
      else;
        set(attacking);
        do; key(attack); while(attacking);
      endif;
    }$$
    ```

## The modern alternative

Every script can also be written in the modern brace syntax, which transpiles to the
exact same runtime:

```text
if attacking {
  unset(attacking)
  log("stopped")
} else {
  set(attacking)
  forever {
    key(attack)
  }
}
```

Read the full comparison in [The DSL Language](dsl-language.md#two-syntaxes-one-runtime).

## Roadmap

The pieces still landing on the Fabric side (the engine already supports all of the
language features they build on):

- In-game **keybind/script editor** GUI
- The **Minecraft-bound action set**: `key`, `keydown`, `keyup`, `press`, `look`,
  `craft`, `pick`, GUI/menu actions, …
- **Built-in variables**: player (`HEALTH`, `XPOS`, `YAW`…), world, input, settings
- **Events**: `onTick`, `onChat`, server join/leave, …
- Per-server configuration profiles

Track parity progress in the [Catalog](../catalog/PARITY.md) once published.
