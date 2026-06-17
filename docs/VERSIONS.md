# Supported Minecraft Versions

MacroMod is built as a [Stonecutter](https://stonecutter.kikugie.dev/) multi-version
Fabric mod. Every Minecraft version listed below is a generated build variant produced
from the same source tree. `./gradlew chiseledBuild` builds them all in one pass; the
per-variant jars land under `fabric/versions/<mc>/build/libs/`.

## Shared toolchain (all versions)

These values live in the root `gradle.properties` and `settings.gradle.kts` and apply to
every targeted Minecraft version (all of 1.21.x is the Java 21 era, so a single toolchain
covers the whole range):

| Component | Version | Where |
| --- | --- | --- |
| Fabric Loom | `1.17.11` | `settings.gradle.kts` (settings buildscript classpath) |
| Gradle | `9.5.1` | `gradle/wrapper/gradle-wrapper.properties` |
| Fabric Loader | `0.19.3` | `gradle.properties` (`deps.fabric_loader`) |
| Fabric Language Kotlin | `1.13.3+kotlin.2.1.21` | `gradle.properties` (`deps.fabric_language_kotlin`) |
| Mappings | Mojang official (Mojmap) | `fabric/build.gradle.kts` |
| Java | 21 | `fabric/build.gradle.kts` |

> **Why Loom 1.17.11 + Gradle 9.5.1?** The Fabric project recommends Loom 1.11 for 1.21.9
> and Loom 1.14 for 1.21.11; Loom 1.17.11 (the newest stable) satisfies both and still
> builds 1.21.1. Loom 1.17.x requires the Gradle plugin API of Gradle 9.5, so the wrapper
> was bumped from 9.0.0 to 9.5.1 (current stable). One Loom + one Gradle covers the whole
> 1.21.x line. The original Loom 1.11.8 was too old for 1.21.10/1.21.11.
>
> Fabric Loader 0.19.3 and Fabric Language Kotlin 1.13.3+kotlin.2.1.21 already exceed the
> minimums for the newest releases (1.21.11 needs Loader >= 0.18.1), so neither was bumped.

## Supported versions

Each Minecraft version has its own `fabric/versions/<mc>/gradle.properties` pinning the
exact Fabric API build for that release (Fabric API ships a distinct build per Minecraft
point release even when the game itself is binary-compatible across two of them).

| Minecraft | Fabric API |
| --- | --- |
| 1.21    | `0.102.0+1.21` |
| 1.21.1  | `0.116.12+1.21.1` |
| 1.21.2  | `0.106.1+1.21.2` |
| 1.21.3  | `0.114.1+1.21.3` |
| 1.21.4  | `0.119.4+1.21.4` |
| 1.21.5  | `0.128.2+1.21.5` |
| 1.21.6  | `0.128.2+1.21.6` |
| 1.21.7  | `0.129.0+1.21.7` |
| 1.21.8  | `0.136.1+1.21.8` |
| 1.21.9  | `0.134.1+1.21.9` |
| 1.21.10 | `0.138.4+1.21.10` |
| 1.21.11 | `0.141.4+1.21.11` |

All twelve 1.21.x point releases that have Fabric support are covered. Fabric API versions
are the latest stable build for each Minecraft version as published on
[Modrinth](https://modrinth.com/mod/fabric-api/versions) /
[maven.fabricmc.net](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/) as of
June 2026.

## Not supported

| Minecraft | Reason |
| --- | --- |
| (none) | Every 1.21.x point release (1.21 through 1.21.11) builds and is included. |

No 1.21.x point release had to be excluded. Snapshot cycles (e.g. the `+26.x` Fabric API
builds for the post-1.21.11 snapshots) are intentionally out of scope — this matrix targets
released Minecraft versions only.
