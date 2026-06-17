plugins {
    id("fabric-loom")
    kotlin("jvm")
}

// DO NOT set group via stonecutter's current version trickery — keep it simple.
group = "dev.macromod"
version = "${property("mod.version")}+${stonecutter.current.version}"
base.archivesName = property("mod.id") as String

// Java 21 for every targeted Minecraft version (1.21.1 / 1.21.5 are both Java-21 era).
val javaVersion = JavaVersion.VERSION_21

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
}

dependencies {
    // Fetch only the Fabric API module(s) we need for the active Minecraft version.
    fun fapi(vararg modules: String) {
        for (m in modules) modImplementation(fabricApi.module(m, property("deps.fabric_api") as String))
    }

    minecraft("com.mojang:minecraft:${stonecutter.current.version}")
    // Official Mojang mappings (Mojmap) instead of Yarn.
    mappings(loom.officialMojangMappings())

    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")

    // Kotlin language adapter — required so the Kotlin entrypoint can be loaded at runtime.
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("deps.fabric_language_kotlin")}")

    // A representative Fabric API module so the dependency wiring is proven per version.
    fapi("fabric-lifecycle-events-v1")

    // The pure-JVM engine: compile against it AND shade it into the remapped mod jar
    // (JIJ). `include` nests the plain jar; `implementation` puts it on the compile/runtime
    // classpath so MacroModClient can construct dev.macromod.engine.ScriptHost.
    implementation(project(":engine"))
    include(project(":engine"))
}

loom {
    runConfigs.all {
        ideConfigGenerated(true)
        // Share one run directory across all generated versions.
        runDir = "../../run"
    }
}

java {
    withSourcesJar()
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
    toolchain { languageVersion = JavaLanguageVersion.of(javaVersion.majorVersion) }
}

kotlin {
    jvmToolchain(javaVersion.majorVersion.toInt())
}

tasks {
    processResources {
        // NOTE: inside this task-configuration block `this` is the task, so use
        // `project.property(...)` — a bare `property(...)` resolves against the task.
        val props = mapOf(
            "id" to project.property("mod.id"),
            "name" to project.property("mod.name"),
            "version" to project.property("mod.version"),
            "minecraft" to project.property("mod.mc_dep"),
        )
        props.forEach { (k, v) -> inputs.property(k, v) }
        filesMatching("fabric.mod.json") { expand(props) }
    }
}
