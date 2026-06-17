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
| 17 | 1.19.2, 1.19.4, 1.20.1, 1.20.2, 1.20.4 |

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

Eighteen point releases across 1.19.x, 1.20.x and 1.21.x are covered. Fabric API versions
are the latest stable build for each Minecraft version as published on
[Modrinth](https://modrinth.com/mod/fabric-api/versions) /
[maven.fabricmc.net](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/) as of
June 2026. A single shared Fabric Language Kotlin (`1.13.3+kotlin.2.1.21`, compatible with
MC 1.14–1.21.6) covers every version, so no per-version FLK override is used.

## Not supported

| Minecraft | Reason |
| --- | --- |
| (none in 1.19.x–1.21.x) | Every targeted point release builds and is included. |

No targeted point release had to be excluded. 1.19.0/1.19.1 and the 1.20.3 / 1.20.5
intermediates are skipped as redundant (1.19.2 covers `>=1.19`, 1.20.4 covers `>=1.20.3`,
1.20.6 covers the 1.20.5 Java-21 cutover). Snapshot cycles (e.g. the `+26.x` Fabric API
builds for the post-1.21.11 snapshots) are intentionally out of scope — this matrix targets
released Minecraft versions only.
