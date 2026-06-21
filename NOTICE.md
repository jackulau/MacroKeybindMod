# NOTICE

Attribution and licensing for MacroKeybindMod and the third-party material it ships.

## MacroKeybindMod — MIT

The source under `engine/`, `fabric/`, and `docs/` is an original reimplementation,
released under the [MIT License](LICENSE).

## Attribution

MacroKeybindMod revives **The Macro / Keybind Mod** by **Mumfrey** (Adam Mummery-Smith,
eq2online) — original package `net.eq2online.macros`, last released for Minecraft 1.12.x
on LiteLoader. MacroKeybindMod is an independent project, not affiliated with or endorsed
by the original author.

## Third-party dependencies

The shipped mod jars depend on, but do not relicense, the following (each under its own license):

| Component | License | Role |
| --- | --- | --- |
| [Fabric Loader](https://github.com/FabricMC/fabric-loader) | Apache-2.0 | mod loader |
| [Fabric API](https://github.com/FabricMC/fabric) | Apache-2.0 | tick / keybind / chat events |
| [Fabric Language Kotlin](https://github.com/FabricMC/fabric-language-kotlin) | Apache-2.0 | Kotlin runtime adapter |
| [Kotlin stdlib](https://github.com/JetBrains/kotlin) | Apache-2.0 | language runtime |

The pure-JVM engine bundled inside each mod jar (Jar-in-Jar) is MacroKeybindMod's own MIT code.

The documentation site vendors [CodeMirror](https://codemirror.net/) 5.x (MIT) under
`docs/javascripts/vendor/` and `docs/stylesheets/vendor/` to power offline syntax
highlighting and the in-browser editor.
