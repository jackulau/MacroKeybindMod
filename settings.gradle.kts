pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
    }
}

// Fabric Loom must be put on the *settings buildscript* classpath (not the pluginManagement
// `plugins {}` block) so we can override its transitive GSON. Older Loom pulls GSON 2.8.9,
// which cannot set Minecraft's VersionsManifest final fields via reflection on JDK 21 (fixed
// in GSON 2.9.1+ via ReflectionAccessFilter). Forcing a modern GSON here reaches Loom because
// it now shares this classloader. The :fabric subproject applies it via `id("fabric-loom")`
// with no version. Minecraft-version-independent, so one Loom variant covers the whole
// 1.21.x range. Loom 1.17.11 is the newest stable: the Fabric blog recommends Loom 1.11 for
// 1.21.9 and Loom 1.14 for 1.21.11, both satisfied here, and it still builds 1.21.1.
buildscript {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
    }
    dependencies {
        classpath("net.fabricmc:fabric-loom:1.17.11")
    }
    configurations.all {
        resolutionStrategy {
            force("com.google.code.gson:gson:2.11.0")
        }
    }
}

plugins {
    // Stonecutter is a SETTINGS plugin: it manages multi-version source sets.
    id("dev.kikugie.stonecutter") version "0.9.6"
    // Foojay toolchain resolver: lets Gradle auto-provision a JDK (e.g. JDK 17 for the
    // 1.19.x/1.20.1-1.20.4 era) when the requested toolchain isn't detected locally.
    // Requires Gradle 8.4+ (wrapper is 9.5.1).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "macromod"

// Pure-JVM scripting engine (no Minecraft deps — the DSL heart, independently testable).
// NOT chiseled: it stays a single normal Kotlin module.
include(":engine")

// Multi-version Fabric mod. Stonecutter versions ONLY this subproject (see create("fabric")
// below); :engine is untouched. Each Minecraft version becomes a generated build variant.
include(":fabric")

stonecutter {
    // Use the Kotlin controller script (stonecutter.gradle.kts) so the aggregate
    // `chiseledBuild` task can be registered against the generated version tree.
    kotlinController = true
    create(":fabric") {
        // Minecraft versions to target. Each gets a versions/<mc>/gradle.properties with
        // its Fabric API / dependency coordinates (and deps.java for the older, Java-17
        // era releases). A single fabric-loom variant + Mojang mappings covers the whole
        // 1.19.x–1.21.x span; per-version Java comes from deps.java (see fabric/build.gradle.kts).
        versions(
            "1.16.5",
            "1.17.1",
            "1.18.2",
            "1.19.2",
            "1.19.4",
            "1.20.1",
            "1.20.2",
            "1.20.4",
            "1.20.6",
            "1.21",
            "1.21.1",
            "1.21.2",
            "1.21.3",
            "1.21.4",
            "1.21.5",
            "1.21.6",
            "1.21.7",
            "1.21.8",
            "1.21.9",
            "1.21.10",
            "1.21.11",
        )
        vcsVersion = "1.21.1"
    }
}
