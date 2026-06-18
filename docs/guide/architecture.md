# Architecture

MacroMod is built so the hard part — the scripting engine — never depends on
Minecraft. This page explains the module layout, the engine internals, and how the
multi-version Fabric build works.

The companion [Architecture Reference](../ARCHITECTURE-REFERENCE.md) maps the original
mod's subsystems (lifecycle, input, events, GUI, config) to their modern Fabric
equivalents. This page covers *our* implementation.

## Module layout

```
macromod/
├── engine/      pure-JVM Kotlin — the DSL. No Minecraft. Unit-tested.
├── fabric/      Stonecutter-managed Fabric mod (23 MC versions). Shades :engine.
├── reference/   decompiled original, for study only (not shipped)
└── docs/        this site
```

- **`:engine`** is a plain Kotlin library. It builds and tests with nothing but the
  Kotlin stdlib and JUnit — no game, no network. This is deliberate: the lexer, parser,
  runtime VM, variable system and expression evaluator are all verifiable in
  milliseconds.
- **`:fabric`** is the Minecraft integration. It depends on `:engine`, **shades it into
  the mod jar** (Fabric's "Jar-in-Jar"), and supplies the Minecraft-bound pieces:
  keybinds, MC actions, variable providers, events, GUI.

## The engine pipeline

Source becomes running behaviour in clear stages:

<figure class="mm-diagram" markdown="0">
<svg viewBox="0 0 760 360" role="img" xmlns="http://www.w3.org/2000/svg" fill="none" stroke="currentColor" aria-label="The engine pipeline: source text → ParamSubstitutor (Phase A) → processed text → ScriptCompiler (Phase B) → a flat List of Instructions → Interpreter → side effects via OutputSink.">
  <rect x="90" y="14" width="320" height="44" rx="10" stroke-width="2"/>
  <text x="250" y="42" text-anchor="middle" stroke="none" fill="currentColor" font-family="ui-monospace,SFMono-Regular,Menlo,monospace" font-size="15">source text</text>
  <line x1="250" y1="58" x2="250" y2="96" stroke-width="2"/>
  <polygon points="250,104 243,90 257,90" fill="currentColor" stroke="none"/>
  <text x="430" y="76" stroke="none" fill="currentColor" font-family="ui-monospace,SFMono-Regular,Menlo,monospace" font-size="12.5" opacity="0.8">ParamSubstitutor · Phase A ($$ codes)</text>
  <rect x="90" y="104" width="320" height="44" rx="10" stroke-width="2"/>
  <text x="250" y="132" text-anchor="middle" stroke="none" fill="currentColor" font-family="ui-monospace,SFMono-Regular,Menlo,monospace" font-size="15">processed text</text>
  <line x1="250" y1="148" x2="250" y2="186" stroke-width="2"/>
  <polygon points="250,194 243,180 257,180" fill="currentColor" stroke="none"/>
  <text x="430" y="166" stroke="none" fill="currentColor" font-family="ui-monospace,SFMono-Regular,Menlo,monospace" font-size="12.5" opacity="0.8">ScriptCompiler · Phase B (split + parse)</text>
  <rect x="90" y="194" width="320" height="50" rx="10" stroke-width="2"/>
  <text x="250" y="216" text-anchor="middle" stroke="none" fill="currentColor" font-family="ui-monospace,SFMono-Regular,Menlo,monospace" font-size="15">List&lt;Instruction&gt;</text>
  <text x="250" y="234" text-anchor="middle" stroke="none" fill="currentColor" font-family="ui-monospace,SFMono-Regular,Menlo,monospace" font-size="11.5" opacity="0.7">ChatLine · Invoke</text>
  <line x1="250" y1="244" x2="250" y2="282" stroke-width="2"/>
  <polygon points="250,290 243,276 257,276" fill="currentColor" stroke="none"/>
  <text x="430" y="262" stroke="none" fill="currentColor" font-family="ui-monospace,SFMono-Regular,Menlo,monospace" font-size="12.5" opacity="0.8">Interpreter · pointer + operator-stack VM</text>
  <rect x="90" y="290" width="320" height="54" rx="10" stroke-width="2"/>
  <text x="250" y="314" text-anchor="middle" stroke="none" fill="currentColor" font-family="ui-monospace,SFMono-Regular,Menlo,monospace" font-size="15">side effects</text>
  <text x="250" y="333" text-anchor="middle" stroke="none" fill="currentColor" font-family="ui-monospace,SFMono-Regular,Menlo,monospace" font-size="11.5" opacity="0.7">chat · log · variable writes → OutputSink</text>
</svg>
</figure>

Key types (package `dev.macromod.engine`):

| Type | Role |
| --- | --- |
| `ScriptHost` | façade — holds the action registry + param config; compiles & runs |
| `MacroScript` | a compiled program (the `List<Instruction>`) |
| `ScriptCompiler` | text → instructions (legacy format) |
| `ModernTranspiler` | modern brace syntax → legacy text → `ScriptCompiler` |
| `Interpreter` | executes the program |
| `ExpressionEvaluator` | precedence-climbing expression parser |
| `VariableRegistry` | scoped variable storage + env providers |
| `ScriptAction` | one DSL verb; built-ins live in `action/builtin/` |

### The runtime VM

A compiled macro is a **flat** instruction list — control-flow nesting is *not*
structural. The interpreter reconstructs it with:

- a single **instruction pointer**, and
- an **operator stack** of frames (max depth 32).

Each frame carries a `conditionalFlag`; the program is "live" only when **every**
frame's flag is set (the AND of the stack). That single rule powers conditionals
(`if` pushes a frame whose flag is the condition) *and* loop bodies.

Loops keep their frame and **rewind the pointer** to the body start to iterate; the
closer (`loop`/`while`/`until`/`next`) decides whether to rewind or pop-and-exit. `for`
and `foreach` carry their iteration state in the frame. `break` simply clears the
nearest loop frame's flag, which gates the rest of the body and makes the next closer
exit.

This mirrors the original engine's behaviour while being, we think, easier to follow —
and it's covered by the runtime test suite (loops, nesting, break, unterminated-block
detection, an infinite-loop step guard).

### Variables

`VariableRegistry` resolves reads in order: **environment providers** (the Fabric host
injects player/world/input state here) → the **scope store** (`@`-prefixed → shared,
otherwise local). Writes route the same way. Values are coerced to the variable's
sigil type on store. Arrays are backed per name with sorted integer keys.

### Expressions

`ExpressionEvaluator` is a small Pratt parser: it lexes numbers, quoted strings,
`true`/`false`, variable references (sigils included) and operators, then parses with
real precedence. A lone `&` is the string sigil; only `&&` is logical-AND — exactly the
ambiguity the original had to handle.

## Multi-version with Stonecutter

The `:fabric` module is managed by [Stonecutter](https://stonecutter.kikugie.dev/),
which compiles **one source tree against many Minecraft versions**. The settings file
declares the targets:

```kotlin title="settings.gradle.kts"
stonecutter {
    create(":fabric") {
        versions("1.21", "1.21.1", /* … */ "1.21.11", "1.20.6", /* … */ "1.19.2")
        vcsVersion = "1.21.1"
    }
}
```

Each version becomes a generated build variant (`:fabric:1.21.9`, …) with its own
per-version properties:

```properties title="fabric/versions/1.21.9/gradle.properties"
deps.fabric_api=0.134.1+1.21.9
mod.mc_dep=>=1.21.9 <=1.21.9
mod.mc_title=1.21.9
deps.java=21
```

`./gradlew chiseledBuild` builds **every** variant, producing one remapped jar per
version. The Java toolchain is selected per version (17 for ≤1.20.4, 21 above) and
auto-provisioned by the Foojay resolver if missing.

!!! info "One Loom, the whole range"
    Fabric Loom is on the *settings buildscript* classpath, so a single Loom version
    must support the entire MC range. We track the newest Loom that does. Mappings are
    **official Mojang mappings** (Mojmap), aligning with where Fabric and the wider
    ecosystem are heading.

### Adding a Minecraft version

1. Find the correct Fabric API build for the MC version.
2. Create `fabric/versions/<mc>/gradle.properties` (the four keys above).
3. Add `<mc>` to the `versions(...)` list in `settings.gradle.kts`.
4. `./gradlew chiseledBuild` and fix any version-specific code with Stonecutter
   comment predicates (`//? if >=1.21 { … }`).

### Adding an action

Because actions self-describe, adding one is a small, local change:

```kotlin
object MyAction : ScriptAction("myaction") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        ctx.output.log("hello from %s".format(ctx.expand(args[0])))
        return ReturnValue.Void
    }
}
```

Register it (engine-level in `defaultActionRegistry()`, or MC-bound via the Fabric
host). Control-flow verbs override `operator` and the loop/condition hooks instead.

## Testing

The engine has a full JUnit suite — parser, runtime, variables, expressions, actions,
parameter substitution, and the modern transpiler. Run it with:

```bash
./gradlew :engine:test
```

It needs no Minecraft and finishes in seconds, which is what keeps iteration on the
language fast.
