# Getting Started

This page takes you from a clean Minecraft install to a working, key-bound macro.

!!! note "Status"
    MacroMod is under active development. The scripting **engine** is complete and
    tested; the **Fabric integration** builds for **23 Minecraft versions** (1.14.4 –
    1.21.11) and loads as a client mod. Input (`key`, `look`, `turn`), navigation
    (`goto` via the built-in A\* pathfinder), player variables, the `onTick`/`onChat`
    events, the module-toggle GUI, and toggle-able **auto-reconnect** are wired; a full
    in-game script editor and the remaining bound actions are on the [roadmap](#roadmap).

## Requirements

| You need | Why |
| --- | --- |
| A supported Minecraft version | 1.14.4 – 1.21.11 — see the [version matrix](../VERSIONS.md) |
| [Fabric Loader](https://fabricmc.net/use/) | The mod loader MacroMod targets |
| [Fabric API](https://modrinth.com/mod/fabric-api) | Standard Fabric library, matched to your MC version |
| [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) | Runtime for the Kotlin entrypoint |
| Java | Bundled with the launcher; 8 for ≤1.16.5, 16 for 1.17.x, 17 for 1.18–1.20.4, 21 for 1.20.5+ |

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

## Built-in keys & toggles

MacroMod registers a few default keybinds (rebind them in *Options → Controls → MacroMod*):

| Key | Does |
| --- | --- |
| ++h++ | run the demo hotkey macro (HUD log + a test jump) |
| ++g++ | demo `goto` — pathfinds a few blocks ahead of you |
| ++right-shift++ | open the **module-toggle GUI** (1.21+) |
| ++"Numpad 5"++ | toggle **auto-reconnect** on/off |

**Auto-reconnect** (off by default) rejoins the **last server** a few seconds after a
disconnect, retrying a capped number of times — handy for flaky connections or queue
restarts. Toggle it with ++"Numpad 5"++ or from the module GUI; a HUD line confirms the new
state. It only fires from a real disconnect, never when you quit to the title or menu.

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

```macro
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

Already wired on the Fabric side: input (`key`, `look`, `turn`), navigation (`goto` + the
built-in A\* pathfinder), player variables (`HEALTH`, `XPOS`, `YAW`, …), the `onTick` /
`onChat` events, a module-toggle GUI, and toggle-able auto-reconnect.

Still landing (the engine already supports the language features these build on):

- A full in-game **keybind/script editor** GUI (today's GUI toggles modules)
- The remaining **bound actions**: `keydown`, `keyup`, `press`, `craft`, `pick`, GUI/menu actions
- More **built-in variables** (world, input, settings) and **events** (server join/leave, …)
- Per-server configuration profiles

Track parity progress in the [Catalog](../catalog/PARITY.md) once published.
