# MacroKeybindMod

[![build](https://github.com/jackulau/MacroKeybindMod/actions/workflows/build.yml/badge.svg)](https://github.com/jackulau/MacroKeybindMod/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.14.4_to_1.21.11-brightgreen.svg)](#supported-versions)

**A modern, multi-version rewrite of the Macro / Keybind Mod** — the long-running
client-side Minecraft mod that binds keys, mouse buttons and menus to *scripts*: from a
one-liner that types a command to a full automation routine with variables, loops,
conditionals, events and pathfinding.

The original (`net.eq2online.macros`, by Mumfrey) froze at **Minecraft 1.12.2** on the
now-defunct LiteLoader. MacroKeybindMod revives it from the ground up on **Fabric**, with
first-class support for **23 Minecraft versions**
(1.14.4 → 1.21.11) built from a single source tree.

- **One engine, every version** — a pure-JVM scripting core with zero Minecraft
  dependencies, shaded into each Fabric build.
- **Two languages, one runtime** — the classic `$${ … }$$` MKB syntax *and* a clean modern
  brace syntax, both compiling to the same instruction set.
- **Its own pathfinding** — a from-scratch A\* over the block grid (walk / diagonal / step /
  fall / parkour), no Baritone. Drive it from a script with `goto(x, y, z)`, and **swap in your
  own algorithm** through the [Pathfinder SPI](docs/guide/pathfinding.md#write-your-own-pathfinder).
- **Auto-reconnect** — opt-in: rejoin the last server automatically after a disconnect (toggle
  via the in-game module GUI or a keybind).
- **Tested** — the engine ships a real unit-test suite (357 tests): lexer, parser, runtime
  VM, variables, expressions, actions, pathfinding.

---

## Quick start

1. Install **[Fabric Loader](https://fabricmc.net/use/installer/)** for your Minecraft version.
2. Download the matching MacroKeybindMod jar from **[`dist/`](dist/)** (see [Supported versions](#supported-versions)).
3. Put it in your `.minecraft/mods/` folder, alongside its dependencies:
   - **[Fabric API](https://modrinth.com/mod/fabric-api)** (matching your Minecraft version)
   - **[Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin)** (`>= 1.13.0`)
4. Launch the **Fabric** profile. Bind a key in *Options → Controls* and start scripting.

> MacroKeybindMod is a **client-side** mod. It does not need to be installed on the server.

---

## Installation in detail

MacroKeybindMod has three runtime dependencies. All three must be in your `mods/` folder:

| Dependency | Why | Where |
| --- | --- | --- |
| **Fabric Loader** `>= 0.16` *(lower on pre-1.19)* | the mod loader itself | [fabricmc.net](https://fabricmc.net/use/installer/) |
| **Fabric API** (per MC version) | tick / keybind / chat events the bridge hooks into | [Modrinth](https://modrinth.com/mod/fabric-api) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/fabric-api) |
| **Fabric Language Kotlin** `>= 1.13.0` | runs the Kotlin entrypoint | [Modrinth](https://modrinth.com/mod/fabric-language-kotlin) |

The pure-JVM engine is **bundled inside** each MacroKeybindMod jar (Jar-in-Jar), so you never
install it separately.

Use the Java version your Minecraft build expects: **Java 21** for 1.20.5+ / the 1.21.x
line, **Java 17** for 1.18–1.20.4, **Java 16** for 1.17.x, **Java 8** for 1.16.5 and older.

---

## Supported versions

23 Minecraft versions are built from one source tree (Stonecutter + Fabric Loom, official
Mojang mappings). Each produces `macromod-0.1.0+<mc>.jar` in [`dist/`](dist/).

| | | | |
| --- | --- | --- | --- |
| 1.14.4 | 1.15.2 | 1.16.5 | 1.17.1 |
| 1.18.2 | 1.19.2 | 1.19.4 | 1.20.1 |
| 1.20.2 | 1.20.4 | 1.20.6 | 1.21   |
| 1.21.1 | 1.21.2 | 1.21.3 | 1.21.4 |
| 1.21.5 | 1.21.6 | 1.21.7 | 1.21.8 |
| 1.21.9 | 1.21.10 | 1.21.11 | |

1.14.4 is the floor — the oldest Minecraft version with official Mojang mappings. The two
oldest targets (1.14.4 / 1.15.2) predate the Fabric key-binding/chat event APIs, so the
in-game bridge there degrades to a logging sink while the engine runs in full.

See **[docs/VERSIONS.md](docs/VERSIONS.md)** for the per-version dependency matrix.

---

## What a macro looks like

**Legacy MKB syntax:**

```
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

**Modern brace syntax:**

```
if automine {
  log("Automine off")
  unset(automine)
} else {
  log("Automine on")
  set(automine)
  forever { key(attack) }
}
```

Both compile to the **same** flat instruction list and run on the **same** virtual machine.

**Try scripts live in your browser** — the docs site ships an interactive
[Script Editor](docs/editor.md) that runs the DSL with no install needed.

---

## Documentation

Full documentation lives in **[`docs/`](docs/)** and builds into a searchable site with
[MkDocs Material](https://squidfunk.github.io/mkdocs-material/):

```bash
pip install mkdocs-material
mkdocs serve          # http://127.0.0.1:8000
```

Highlights:

- **[Getting Started](docs/guide/getting-started.md)** — install, bind a key, first macro
- **[The DSL Language](docs/guide/dsl-language.md)** — variables, expressions, control flow, both syntaxes
- **[Parameters & Variables](docs/guide/parameters.md)** — `$$` codes and `%var%` expansion
- **[Pathfinding](docs/guide/pathfinding.md)** — the A\* engine and `goto`
- **[Architecture](docs/guide/architecture.md)** — engine ↔ Fabric bridge
- **[Catalog](docs/catalog/PARITY.md)** — every action, variable and event (parity audit vs the original)
- **[Reference](docs/DSL-REFERENCE.md)** — the byte-level DSL spec

---

## Build from source

Requires a JDK (21 recommended — Gradle auto-provisions older JDKs via the Foojay resolver).
The Gradle wrapper pins everything else (Gradle 9.5.1, Kotlin 2.1.0, Loom 1.17.11,
Stonecutter 0.9.6).

```bash
# Run the engine's unit tests (pure JVM, no Minecraft needed)
./gradlew :engine:test

# Build the mod for the active version (default 1.21.1)
./gradlew :fabric:build

# Build the mod for EVERY supported version (writes 23 jars)
./gradlew chiseledBuild

# Switch the active dev version
./gradlew "Set active project to 1.20.1"
```

Built jars land in `fabric/versions/<mc>/build/libs/`. The release copies in
[`dist/`](dist/) are collected from there — see **[CONTRIBUTING.md](CONTRIBUTING.md)** for
the full workflow.

---

## Project layout

```
mc_macromod/
├─ engine/          Pure-JVM scripting engine (DSL lexer → compiler → VM, pathfinding,
│                   modules). No Minecraft deps. 357 unit tests.
├─ fabric/          Multi-version Fabric mod (Stonecutter). Keybinds, MC actions, GUI,
│                   events, navigation. Shades :engine via Jar-in-Jar.
│  └─ versions/     Per-Minecraft-version gradle.properties (deps + Java level).
├─ docs/            MkDocs documentation site (guides, catalog, reference, editor).
├─ dist/            Ready-to-install mod jars (one per supported version) + checksums.
├─ settings.gradle.kts   Stonecutter version list + Loom/Foojay setup.
└─ mkdocs.yml       Docs site config.
```

---

## License & responsible use

MacroKeybindMod's own source (`engine/`, `fabric/`, `docs/`) is released under the
**[MIT License](LICENSE)**. See **[NOTICE.md](NOTICE.md)** for attribution to the original
Macro / Keybind Mod and the licenses of the bundled dependencies.

> [!WARNING]
> Automating gameplay can violate a server's rules — for example, Hypixel's Terms of
> Service. MacroKeybindMod is a general-purpose client tool; how you use it, and any consequences,
> are your responsibility.
