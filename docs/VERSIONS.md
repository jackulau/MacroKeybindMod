# Supported Minecraft Versions

MacroMod is built as a [Stonecutter](https://stonecutter.kikugie.dev/) multi-version
Fabric mod. Every Minecraft version listed below is a generated build variant produced
from the same source tree. `./gradlew chiseledBuild` builds them all in one pass; the
per-variant jars land under `fabric/versions/<mc>/build/libs/`.

## Shared toolchain (all versions)

These values live in the root `gradle.properties` and `settings.gradle.kts` and apply to
every targeted Minecraft version across the whole 1.19.x–1.21.x span:

| Component | Version | Where |
| --- | --- | --- |
| Fabric Loom | `1.17.11` | `settings.gradle.kts` (settings buildscript classpath) |
| Gradle | `9.5.1` | `gradle/wrapper/gradle-wrapper.properties` |
| Foojay toolchain resolver | `1.0.0` | `settings.gradle.kts` (`plugins {}`) |
| Fabric Loader | `0.19.3` | `gradle.properties` (`deps.fabric_loader`) |
| Fabric Language Kotlin | `1.13.3+kotlin.2.1.21` | `gradle.properties` (`deps.fabric_language_kotlin`) |
| Mappings | Mojang official (Mojmap) | `fabric/build.gradle.kts` |

> **Why Loom 1.17.11 + Gradle 9.5.1?** The Fabric project recommends Loom 1.11 for 1.21.9
> and Loom 1.14 for 1.21.11; Loom 1.17.11 (the newest stable) satisfies both and still
> builds 1.21.1. Loom 1.17.x requires the Gradle plugin API of Gradle 9.5, so the wrapper
> was bumped from 9.0.0 to 9.5.1 (current stable). One Loom + one Gradle covers the whole
> 1.19.x–1.21.x line (Loom is backward compatible well past 1.19). The original Loom 1.11.8
> was too old for 1.21.10/1.21.11.
>
> Fabric Loader 0.19.3 and Fabric Language Kotlin 1.13.3+kotlin.2.1.21 already exceed the
> minimums for the newest releases (1.21.11 needs Loader >= 0.18.1) AND are still compatible
> with the oldest targets — FLK 1.13.3+kotlin.2.1.21 is marked compatible with Minecraft
> 1.14–1.21.6, so no per-version FLK or Loader override was needed.

### Per-version Java

Minecraft switched its required Java from 17 to 21 with the 1.20.5 snapshot cycle, so Java
is **not** shared — it is selected per version via a `deps.java` property in each
`fabric/versions/<mc>/gradle.properties`. `fabric/build.gradle.kts` reads it
(`val javaVersion = JavaVersion.toVersion((findProperty("deps.java") ?: "21"))`) and applies
it to the Java + Kotlin toolchains, and `processResources` templates the `java` dependency in
`fabric.mod.json` as `>=<deps.java>`. The default is `21`, so the 1.21.x variants need no
property.

| Java | Minecraft versions |
| --- | --- |
| 21 | 1.20.6, all of 1.21.x |
| 17 | 1.18.2, 1.19.2, 1.19.4, 1.20.1, 1.20.2, 1.20.4 |

> **Java 17 is the hard floor.** The shaded `:engine` module is compiled to Java-17 bytecode
> (`jvmToolchain(17)`), and Gradle's variant-aware resolution refuses to put a Java-17 library
> on a consumer whose toolchain is an older JVM. Minecraft 1.17.1 requires Java 16 and 1.16.5
> requires Java 8, so neither can shade the engine without lowering its target — which would
> break every Java-17 MC variant that also shades it. **1.18.2 (the newest Java-17 Minecraft)
> is therefore the supported floor.** See **Not supported** below.

The **Foojay toolchain resolver** (`org.gradle.toolchains.foojay-resolver-convention`
`1.0.0`) is added so Gradle can auto-download a JDK 17 if a local one isn't detected. (On the
reference build box a Temurin 17 was already present, so no download occurred.)

> **Engine toolchain note.** The pure-JVM `:engine` module is compiled with `jvmToolchain(17)`
> (was 21). Gradle's variant-aware dependency resolution refuses to put a Java-21 library on a
> Java-17 consumer's classpath, which broke the 1.19.x/1.20.1–1.20.4 variants that shade the
> engine (JIJ). The engine uses only Kotlin stdlib (`kotlin.collections.ArrayDeque.addFirst`
> etc.) with no Java-21-only APIs, so targeting 17 is behavior-neutral and 17 bytecode runs on
> the Java-21 variants too. `./gradlew :engine:test` stays green at 69 tests.

## Supported versions

Each Minecraft version has its own `fabric/versions/<mc>/gradle.properties` pinning the
exact Fabric API build for that release (Fabric API ships a distinct build per Minecraft
point release even when the game itself is binary-compatible across two of them) and, for
the pre-1.20.5 releases, a `deps.java=17`.

| Minecraft | Fabric API | Java | FLK |
| --- | --- | --- | --- |
| 1.18.2  | `0.77.0+1.18.2`   | 17 | `1.9.6+kotlin.1.8.22` (override) |
| 1.19.2  | `0.77.0+1.19.2`   | 17 | `1.13.3+kotlin.2.1.21` (shared) |
| 1.19.4  | `0.87.2+1.19.4`   | 17 | `1.13.3+kotlin.2.1.21` (shared) |
| 1.20.1  | `0.92.9+1.20.1`   | 17 | `1.13.3+kotlin.2.1.21` (shared) |
| 1.20.2  | `0.91.6+1.20.2`   | 17 | `1.13.3+kotlin.2.1.21` (shared) |
| 1.20.4  | `0.97.1+1.20.4`   | 17 | `1.13.3+kotlin.2.1.21` (shared) |
| 1.20.6  | `0.100.8+1.20.6`  | 21 | `1.13.3+kotlin.2.1.21` (shared) |
| 1.21    | `0.102.0+1.21`    | 21 | `1.13.3+kotlin.2.1.21` (shared) |
| 1.21.1  | `0.116.12+1.21.1` | 21 | `1.13.3+kotlin.2.1.21` (shared) |
| 1.21.2  | `0.106.1+1.21.2`  | 21 | `1.13.3+kotlin.2.1.21` (shared) |
| 1.21.3  | `0.114.1+1.21.3`  | 21 | `1.13.3+kotlin.2.1.21` (shared) |
| 1.21.4  | `0.119.4+1.21.4`  | 21 | `1.13.3+kotlin.2.1.21` (shared) |
| 1.21.5  | `0.128.2+1.21.5`  | 21 | `1.13.3+kotlin.2.1.21` (shared) |
| 1.21.6  | `0.128.2+1.21.6`  | 21 | `1.13.3+kotlin.2.1.21` (shared) |
| 1.21.7  | `0.129.0+1.21.7`  | 21 | `1.13.3+kotlin.2.1.21` (shared) |
| 1.21.8  | `0.136.1+1.21.8`  | 21 | `1.13.3+kotlin.2.1.21` (shared) |
| 1.21.9  | `0.134.1+1.21.9`  | 21 | `1.13.3+kotlin.2.1.21` (shared) |
| 1.21.10 | `0.138.4+1.21.10` | 21 | `1.13.3+kotlin.2.1.21` (shared) |
| 1.21.11 | `0.141.4+1.21.11` | 21 | `1.13.3+kotlin.2.1.21` (shared) |

Nineteen point releases across 1.18.x, 1.19.x, 1.20.x and 1.21.x are covered. Fabric API
versions are the latest stable build for each Minecraft version as published on
[Modrinth](https://modrinth.com/mod/fabric-api/versions) /
[maven.fabricmc.net](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/) as of
June 2026. The shared Fabric Language Kotlin (`1.13.3+kotlin.2.1.21`, compatible with MC
1.14–1.21.6) covers every version **from 1.19 up**; 1.18.2 predates that FLK's lower bound,
so it pins `1.9.6+kotlin.1.8.22` via a `deps.fabric_language_kotlin` override in
`fabric/versions/1.18.2/gradle.properties` (which also lowers `deps.fabric_loader` to
`0.14.25` and the `fabric.mod.json` loader/FLK depends floors via `mod.loader_dep`/
`mod.flk_dep`). Despite that FLK bundling Kotlin 1.8, the JIJ'd `:engine` (Kotlin 2.1, Java-17
bytecode) builds cleanly for 1.18.2 — the engine jar is shaded as a binary, so its compile
toolchain is independent of the runtime Kotlin adapter.

### Logging facade (Stonecutter swap)

Fabric only re-exposes SLF4J from Minecraft 1.19 onward. Below 1.19, `MacroModClient` cannot
rely on `org.slf4j.LoggerFactory`, so the logger import + field are wrapped in a Stonecutter
conditional (`//? if >=1.19 { … //?} else /* … */`) that falls back to Minecraft's
always-present Log4j2 (`org.apache.logging.log4j.LogManager`). Both APIs accept `{}`
placeholders, so the `logger.info(…)` call sites are identical across the swap and the engine
call is unchanged. The committed source-of-truth is the active version (1.21.1 ⇒ SLF4J live);
the built `1.18.2` jar correctly carries the Log4j2 branch.

## Not supported (and why)

| Minecraft | Required Java | Blocker |
| --- | --- | --- |
| 1.17.1 | 16 | **Engine toolchain (Java 17).** `:engine` is compiled to Java-17 bytecode and is shaded (JIJ) into every variant. Gradle's variant-aware resolution fails: *"looking for a library compatible with JVM runtime version 16, but 'project :engine' is only compatible with JVM runtime version 17 or newer."* |
| 1.16.5 | 8  | **Engine toolchain (Java 17).** Same failure at JVM runtime version 8. Even if resolution were bypassed, a Java-8 JVM cannot load the engine's major-version-61 class files. |

Both excluded versions hit the **same root blocker**: the shaded `:engine` targets Java 17,
which is itself a deliberate floor (it was lowered from 21 — see the *Java 17 is the hard
floor* note above — to serve the Java-17 MC variants; it cannot be lowered further without an
older-Java engine source set). Loom `1.17.11` itself **can** download and remap 1.17.1 and
1.16.5 (verified — Loom range is *not* the limit), and a correct per-era Fabric API / FLK /
Loader / Log4j-logging configuration was prepared for both, so the *only* thing standing
between MacroMod and a sub-1.18 floor is the engine bytecode target. Resolving it would
require giving `:engine` a Java-8 (or multi-release) variant — out of scope here.

Other gaps are intentional, not blocked: 1.19.0/1.19.1 and the 1.20.3 / 1.20.5 intermediates
are skipped as redundant (1.19.2 covers `>=1.19`, 1.20.4 covers `>=1.20.3`, 1.20.6 covers the
1.20.5 Java-21 cutover). Snapshot cycles (e.g. the `+26.x` Fabric API builds for the
post-1.21.11 snapshots) are out of scope — this matrix targets released Minecraft versions
only.
