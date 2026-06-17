// Stonecutter CONTROLLER script for the :fabric version tree (auto-managed by Stonecutter;
// it lives here — not at repo root — because settings.gradle.kts calls `create(":fabric")`,
// so Stonecutter resolves the controller relative to the :fabric project dir). This is the
// only place the `stonecutter` controller extension is in scope. The per-version mod build
// logic stays in fabric/build.gradle.kts (applied to each generated 1.21.x subproject).
plugins {
    id("dev.kikugie.stonecutter")
}
stonecutter active "1.21.1"

// `./gradlew chiseledBuild` builds every generated :fabric variant (1.21 .. 1.21.11) in one
// invocation. Stonecutter 0.9.x exposes no built-in chiseled helper, so we aggregate the per
// -version `build` tasks: `stonecutter.tasks.named("build")` returns a map of version node ->
// its `build` TaskProvider; depending on its values runs them all.
tasks.register("chiseledBuild") {
    group = "project"
    description = "Builds the mod for every targeted Minecraft version."
    dependsOn(stonecutter.tasks.named("build").map { it.values })
}
