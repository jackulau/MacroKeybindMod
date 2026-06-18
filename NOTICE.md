# NOTICE

This file records attribution and licensing for MacroMod and the third-party
material in this repository.

## MacroMod's own code — MIT, clean-room

The MacroMod source under `engine/`, `fabric/`, and `docs/` is an original,
**clean-room** reimplementation released under the [MIT License](LICENSE).

It was written by studying the *behavior* and *DSL grammar* of the original
Macro / Keybind Mod — language grammars and observable behavior are not themselves
copyrightable — **without copying its code**. No source from the original mod has been
copied into `engine/`, `fabric/`, or `docs/`.

## The original mod — attribution

MacroMod is a revival of **The Macro / Keybind Mod** by **Mumfrey**
(Adam Mummery-Smith) / eq2online — original package `net.eq2online.macros`,
last released as version 0.15.4 for Minecraft 1.12.x on LiteLoader. The original mod
is **"All Rights Reserved."** MacroMod is an independent project and is **not**
affiliated with, endorsed by, or derived from the original author's code.

## `reference/` — study only, not redistributed

`reference/decompiled/` contains a decompilation of the original mod, kept **solely**
for behavioral study while building the clean-room rewrite. It is:

- **NOT** part of the shipped mod (the `dist/` jars contain only MacroMod + the bundled engine),
- **NOT** to be redistributed or pushed to any public remote,
- **NOT** to be copied verbatim into MacroMod's source.

See [`reference/NOTICE.md`](reference/NOTICE.md) for the per-directory terms.

## Third-party dependencies

The shipped mod jars depend on, but do not relicense, the following (each under its own license):

| Component | License | Role |
| --- | --- | --- |
| [Fabric Loader](https://github.com/FabricMC/fabric-loader) | Apache-2.0 | mod loader |
| [Fabric API](https://github.com/FabricMC/fabric) | Apache-2.0 | tick / keybind / chat events |
| [Fabric Language Kotlin](https://github.com/FabricMC/fabric-language-kotlin) | Apache-2.0 | Kotlin runtime adapter |
| [Kotlin stdlib](https://github.com/JetBrains/kotlin) | Apache-2.0 | language runtime |

The pure-JVM engine bundled inside each mod jar (Jar-in-Jar) is MacroMod's own MIT code.

The documentation site vendors [CodeMirror](https://codemirror.net/) 5.x (MIT) under
`docs/javascripts/vendor/` and `docs/stylesheets/vendor/` to power offline syntax
highlighting and the in-browser editor.
