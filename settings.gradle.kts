pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
    }
}

rootProject.name = "macromod"

// Pure-JVM scripting engine (no Minecraft deps — the DSL heart, independently testable).
include(":engine")

// NOTE: the Stonecutter-managed multi-version `:fabric` mod and the `:pathfinding`
// module are introduced in later deliverables (D10 / D16). The engine builds and
// tests on its own so the heart of the rewrite is verifiable without Minecraft.
