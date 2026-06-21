# Supported Minecraft Versions

MacroKeybindMod is built as a [Stonecutter](https://stonecutter.kikugie.dev/) multi-version
Fabric mod. Every Minecraft version listed below is a generated build variant produced
from the same source tree. `./gradlew chiseledBuild` builds them all in one pass; the
per-variant jars land under `fabric/versions/<mc>/build/libs/`.

## Shared toolchain (all versions)

These values live in the root `gradle.properties` and `settings.gradle.kts` and apply to
every targeted Minecraft version across the whole 1.14.x–1.21.x span:

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
| 16 | 1.17.1 |
| 8  | 1.16.5, 1.15.2, 1.14.4 |

> **Java 8 is the floor.** The shaded `:engine` module is compiled to **Java-8 bytecode**
> (`jvmToolchain(8)`). Gradle's variant-aware resolution refuses to put a library on a consumer
> whose toolchain is an *older* JVM than the library's target, so the engine's target must be at
> or below the lowest Minecraft variant's Java. Minecraft 1.17.1 requires Java 16 and 1.16.5
> requires Java 8, as do 1.15.2 and 1.14.4; targeting the engine at Java 8 makes it consumable by
> **every** variant from 1.14.4 (Java 8) up through 1.21.x (Java 21), since Java-8
> (major-version-52) class files load on every newer JVM. The Java floor is not the limiting
> factor — see the hard floor note below.

The **Foojay toolchain resolver** (`org.gradle.toolchains.foojay-resolver-convention`
`1.0.0`) is added so Gradle can auto-download a JDK when a local one isn't detected. On the
reference build box Temurin 17 and JDK 8 were already present; **JDK 16 (for 1.17.1) was
auto-provisioned by Foojay** on first build.

> **Engine toolchain note.** The pure-JVM `:engine` module is compiled with `jvmToolchain(8)`
> (was 21, then 17). Gradle's variant-aware dependency resolution refuses to put a library on a
> consumer whose toolchain is an older JVM than the library's target, which previously blocked
> the Java-16 (1.17.1) and Java-8 (1.16.5) variants that shade the engine (JIJ). The engine uses
> only Kotlin stdlib (`kotlin.collections.ArrayDeque.addFirst` etc.) with no APIs above Java 8,
> so targeting 8 is behavior-neutral and the Java-8 (major-version-52) bytecode runs on every
> newer-JVM variant too. `./gradlew :engine:test` stays green at 361 tests.

## Supported versions

Each Minecraft version has its own `fabric/versions/<mc>/gradle.properties` pinning the
exact Fabric API build for that release (Fabric API ships a distinct build per Minecraft
point release even when the game itself is binary-compatible across two of them) and, for
the pre-1.20.5 releases, a `deps.java=17`.

| Minecraft | Fabric API | Java | FLK |
| --- | --- | --- | --- |
| 1.14.4  | `0.28.5+1.14`     | 8  | `1.3.50+build.3` (override) |
| 1.15.2  | `0.28.5+1.15`     | 8  | `1.3.61+build.1` (override) |
| 1.16.5  | `0.42.0+1.16`     | 8  | `1.6.5+kotlin.1.5.31` (override) |
| 1.17.1  | `0.46.1+1.17`     | 16 | `1.8.7+kotlin.1.7.22` (override) |
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

Twenty-three point releases across 1.14.x, 1.15.x, 1.16.x, 1.17.x, 1.18.x, 1.19.x, 1.20.x and
1.21.x are covered. Fabric API versions are the latest stable build for each Minecraft version as
published on [Modrinth](https://modrinth.com/mod/fabric-api/versions) /
[maven.fabricmc.net](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/) as of
June 2026 (`0.46.1+1.17` is the last `+1.17` build before Fabric API rolled to `+1.18`;
`0.42.0+1.16` is the last `+1.16` build before it rolled to `+1.17`). The shared Fabric
Language Kotlin (`1.13.3+kotlin.2.1.21`, compatible with MC 1.14–1.21.6) covers every version
**from 1.19 up**; the five pre-1.19 releases predate that FLK's practical era, so each pins an
era-appropriate FLK via a `deps.fabric_language_kotlin` override in its
`fabric/versions/<mc>/gradle.properties` — 1.18.2 → `1.9.6+kotlin.1.8.22`, 1.17.1 →
`1.8.7+kotlin.1.7.22`, 1.16.5 → `1.6.5+kotlin.1.5.31`, 1.15.2 → `1.3.61+build.1` (the last
`+1.15`-era FLK), 1.14.4 → `1.3.50+build.3` (the last `+1.14`-era FLK, old `+build.N` format
predating Kotlin-version tagging). Each also lowers `deps.fabric_loader`
to `0.14.25` and the `fabric.mod.json` loader/FLK depends floors via `mod.loader_dep`/
`mod.flk_dep` (the pre-1.16 pair use `mod.flk_dep=>=1.3.0`). Despite those FLKs bundling older
Kotlin (the 1.14/1.15 ones declare a transitive Loader as old as `0.4.9+build.160`, superseded
by the forced `0.14.25`), the JIJ'd `:engine` (Kotlin 2.1, Java-8 bytecode) builds cleanly for
all of them — the engine jar is shaded as a binary, so its compile toolchain is independent of
the runtime Kotlin adapter. Fabric API for 1.14/1.15 is `0.28.5+1.14` / `0.28.5+1.15`, the last
stable builds before Fabric API rolled to `+1.16`.

### Logging facade (Stonecutter swap)

Fabric only re-exposes SLF4J from Minecraft 1.19 onward. Below 1.19, `MacroModClient` cannot
rely on `org.slf4j.LoggerFactory`, so the logger import + field are wrapped in a Stonecutter
conditional (`//? if >=1.19 { … //?} else /* … */`) that falls back to Minecraft's
always-present Log4j2 (`org.apache.logging.log4j.LogManager`). Both APIs accept `{}`
placeholders, so the `logger.info(…)` call sites are identical across the swap and the engine
call is unchanged. The committed source-of-truth is the active version (1.21.1 ⇒ SLF4J live);
the built `1.18.2`, `1.17.1` and `1.16.5` jars all correctly carry the Log4j2 branch (verified
in the compiled `MacroModClient.class` constant pool).

### Previously blocked, now supported (1.17.1, 1.16.5, 1.15.2 & 1.14.4)

1.17.1 (Java 16) and 1.16.5 (Java 8) were once excluded because the shaded `:engine` targeted
Java-17 bytecode and Gradle's variant-aware resolution refuses to put a library on a consumer
with an older JVM (*"looking for a library compatible with JVM runtime version 16/8, but
'project :engine' is only compatible with JVM runtime version 17 or newer"*; a Java-8 JVM also
cannot load major-version-61 class files). Lowering the engine to `jvmToolchain(8)` removed
that blocker for **both** directions at once: Java-8 (major-version-52) bytecode satisfies the
Java-8 and Java-16 consumers *and* still loads on the Java-17/Java-21 variants. With per-era
Fabric API / FLK / Loader pinned (see the table above) and the existing `>=1.19` Log4j-logging
swap covering both, each builds cleanly and JIJ's the engine — verified: the engine jar inside
the 1.16.5 mod jar is major-version-52, and `./gradlew chiseledBuild :engine:test` is
`BUILD SUCCESSFUL` across all 23 variants with 92 engine tests green.

**1.15.2 and 1.14.4 (Fabric's true floor) build with no new machinery.** Both are Java 8, so the
engine JIJs cleanly (verified major-version-52 inside both mod jars); the existing `>=1.19`
Log4j-logging Stonecutter swap already covers them; Loom 1.17.11 remaps both with
`officialMojangMappings()` — Mojang's mapping files exist from 1.14.4 onward, and Loom is tested
down to 1.14.4. Only per-version `gradle.properties` (Fabric API `0.28.5+1.14`/`0.28.5+1.15`,
era FLK, Loader `0.14.25`, `deps.java=8`) plus the `settings.gradle.kts` `versions(...)` entries
were needed. **No Loom downgrade was required** (downgrading would have broken 1.21.10/1.21.11).

## Hard floor: 1.14.4

**1.14.4 is the hard floor, because it is the oldest Minecraft version with official Mojang
mapping files.** `fabric/build.gradle.kts` resolves mappings via `loom.officialMojangMappings()`,
which downloads Mojang's published `client.txt` ProGuard mappings for the active version. Mojang
first published these with the **1.14.4** release; **1.14.0–1.14.3 have no Mojmap at all**, so
Loom cannot remap them on this Mojmap path and they are unreachable without switching the whole
project to Yarn (out of scope). 1.14.4 is also Fabric's own oldest tested target. The floor is
therefore the *mappings*, not the engine's Java-8 bytecode and not Loom — Loom 1.17.11 remaps
1.14.4/1.15.2 fine, and no Loom downgrade was needed (which would have broken 1.21.10/1.21.11).

## Not supported (and why)

Above the floor, no Minecraft version is blocked by a toolchain or tooling limit. Remaining gaps
are intentional, not blocked: 1.19.0/1.19.1 and the 1.20.3 / 1.20.5 intermediates are skipped as
redundant (1.19.2 covers `>=1.19`, 1.20.4 covers `>=1.20.3`, 1.20.6 covers the 1.20.5 Java-21
cutover); 1.14.x/1.15.x point releases below the final `.4`/`.2` are likewise skipped (the final
patch of each is the meaningful target). 1.14.0–1.14.3 are *blocked*, not merely skipped — no
Mojang mappings exist (see the hard floor above), and all pre-1.14 releases predate Fabric
entirely. Snapshot cycles (e.g. the `+26.x` Fabric API builds for the post-1.21.11 snapshots)
are out of scope — this matrix targets released Minecraft versions only.
