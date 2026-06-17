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
// `plugins {}` block) so we can override its transitive GSON. Loom 1.11.8 pulls GSON 2.8.9,
// which cannot set Minecraft's VersionsManifest final fields via reflection on JDK 21 (fixed
// in GSON 2.9.1+ via ReflectionAccessFilter). Forcing a modern GSON here reaches Loom because
// it now shares this classloader. The :fabric subproject applies it via `id("fabric-loom")`
// with no version. Minecraft-version-independent, so one Loom variant covers 1.21.1 and 1.21.5.
buildscript {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
    }
    dependencies {
        classpath("net.fabricmc:fabric-loom:1.11.8")
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
}

rootProject.name = "macromod"

// Pure-JVM scripting engine (no Minecraft deps — the DSL heart, independently testable).
// NOT chiseled: it stays a single normal Kotlin module.
include(":engine")

// Multi-version Fabric mod. Stonecutter versions ONLY this subproject (see create("fabric")
// below); :engine is untouched. Each Minecraft version becomes a generated build variant.
include(":fabric")

stonecutter {
    create(":fabric") {
        // Minecraft versions to target. Each gets a versions/<mc>/gradle.properties with
        // its Fabric API / dependency coordinates. Both are Java-21 era, so a single
        // fabric-loom variant + Mojang mappings works for all of them.
        versions("1.21.1", "1.21.5")
        vcsVersion = "1.21.1"
    }
}
