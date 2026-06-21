# Contributing to MacroKeybindMod

Thanks for your interest in MacroKeybindMod. This guide covers building, testing, and the
multi-version workflow.

## Prerequisites

- **JDK 21** (recommended). Gradle auto-provisions older JDKs (8/16/17) for older Minecraft
  versions via the Foojay toolchain resolver, so you only need one JDK installed.
- **Git**.
- Everything else is pinned by the Gradle wrapper: Gradle 9.5.1, Kotlin 2.1.0,
  Fabric Loom 1.17.11, Stonecutter 0.9.6.

Use `./gradlew` (the wrapper) — do not rely on a system Gradle.

## Repository layout

| Path | What |
| --- | --- |
| `engine/` | Pure-JVM scripting engine (DSL lexer → compiler → VM, pathfinding, modules). No Minecraft deps. |
| `fabric/` | Multi-version Fabric mod (Stonecutter). Shades `:engine` via Jar-in-Jar. |
| `fabric/versions/<mc>/` | Per-version `gradle.properties` (Fabric API coords, Java level, dep floors). |
| `docs/` | MkDocs documentation site. |
| `dist/` | Ready-to-install jars + checksums (tracked in git). |

## The engine (no Minecraft needed)

The engine is a normal single Kotlin module. Develop and test it without touching Fabric:

```bash
./gradlew :engine:test       # 357 unit tests
./gradlew :engine:build
```

## The Fabric mod (Stonecutter, 23 versions)

`:fabric` is Stonecutter-managed: one source tree compiled against every Minecraft version
in `settings.gradle.kts`. An "active" version is selected for day-to-day work.

```bash
# Switch the active dev version (default is 1.21.1, the vcsVersion)
./gradlew "Set active project to 1.20.1"

# Build the active version
./gradlew :fabric:build

# Build EVERY supported version at once (writes 23 jars under fabric/versions/<mc>/build/libs/)
./gradlew chiseledBuild
```

Version-specific source differences use Stonecutter's comment gates, e.g.:

```kotlin
//? if >=1.16 {
keyBinding = KeyBindingHelper.registerKeyBinding(...)
//?}
```

The build-script analogue is `stonecutter.eval(stonecutter.current.version, ">=1.16")`
(see `fabric/build.gradle.kts`).

### Adding a new Minecraft version

1. Add the version string to the `versions( … )` list in `settings.gradle.kts`.
2. Create `fabric/versions/<mc>/gradle.properties` with at least:
   ```properties
   deps.fabric_api=<fabric-api-version>+<mc>
   mod.mc_dep=>=<floor> <=<mc>
   mod.mc_title=<mc>
   ```
   Older (pre-1.21) versions also set `deps.java`, `mod.loader_dep`, and `mod.flk_dep`.
3. `./gradlew "Set active project to <mc>"` then `./gradlew :fabric:build` and fix any gates.

## Refreshing the shipped jars (`dist/`)

After a release-worthy change, rebuild all versions and regenerate `dist/`:

```bash
./gradlew chiseledBuild
bash tools/update-dist.sh        # collects jars, regenerates MANIFEST.md + checksums.sha256
```

`tools/update-dist.sh` copies each `macromod-0.1.0+<mc>.jar` into `dist/`, writes
`dist/checksums.sha256`, and regenerates `dist/MANIFEST.md`. Commit the result.

## Documentation

```bash
pip install mkdocs-material
mkdocs serve            # http://127.0.0.1:8000
mkdocs build --strict   # CI-style check: fails on broken nav/links
```

## Publishing the docs (GitHub Pages)

The site auto-deploys via `.github/workflows/docs.yml` (build `--strict` then
`mkdocs gh-deploy`). To turn it on after pushing to GitHub:

1. In `mkdocs.yml`, replace the `OWNER`/`REPO` placeholders in `site_url`, `repo_url`,
   and `repo_name` with your repository.
2. Push to `master` (or `main`). The **docs** workflow runs and pushes the built site to
   a `gh-pages` branch.
3. In the repo, go to **Settings → Pages → Source: Deploy from a branch → `gh-pages` / `(root)`**.
4. The site appears at the `site_url` you set. To deploy by hand: `mkdocs gh-deploy --force`.

`.github/workflows/build.yml` runs the engine test suite on every push (CI for the code).

## Commit style

Plain, conventional commit subjects (imperative, no issue/goal numbers in the subject line).
Keep engine changes and Fabric changes in separate commits where practical, and run
`./gradlew :engine:test` before committing engine changes.
