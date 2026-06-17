plugins {
    id("fabric-loom")
    kotlin("jvm")
}

// DO NOT set group via stonecutter's current version trickery — keep it simple.
group = "dev.macromod"
version = "${property("mod.version")}+${stonecutter.current.version}"
base.archivesName = property("mod.id") as String

// Per-version Java toolchain. The 1.21.x line (and 1.20.6/1.20.5+) is the Java-21 era;
// 1.20.1/1.20.2/1.20.4 and 1.19.x are the Java-17 era. Each version's
// fabric/versions/<mc>/gradle.properties sets deps.java; the default is 21 so the 1.21.x
// versions keep working without one. With the Foojay resolver (settings.gradle.kts) Gradle
// can auto-provision JDK 17 if it isn't already installed.
val javaVersion = JavaVersion.toVersion((project.findProperty("deps.java") ?: "21").toString())

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

    // Fabric API modules the bridge needs. lifecycle-events (END_CLIENT_TICK) and
    // key-binding (KeyBindingHelper) are the v1 APIs used by the keybind/tick wiring; they
    // only exist from the 1.16 era, so they (and the message-receive module) are added
    // conditionally per version. The two oldest targets (1.14.4 / 1.15.2) get only the base
    // lifecycle module that they ship, and the bridge there degrades to a logging sink — this
    // mirrors the >=1.16 / >=1.19.3 Stonecutter gates in MacroModClient.kt so the mod still
    // builds on every version. `stonecutter.eval(current, "..")` is the build-script analogue
    // of the source `//? if ..` comment gate.
    fapi("fabric-lifecycle-events-v1")
    if (stonecutter.eval(stonecutter.current.version, ">=1.16")) {
        // KeyBindingHelper + KeyMapping registration.
        fapi("fabric-key-binding-api-v1")
    }
    if (stonecutter.eval(stonecutter.current.version, ">=1.19.3")) {
        // ClientReceiveMessageEvents (onChat dispatch) — first-party only from 1.19.3.
        fapi("fabric-message-api-v1")
    }

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
            // Java dependency in fabric.mod.json: ">=<deps.java>" so 1.19/1.20 don't demand
            // Java 21. Falls back to the deps.java default (21) for the 1.21.x versions.
            "java" to ">=${project.findProperty("deps.java") ?: "21"}",
            // Loader / FLK minimums in fabric.mod.json. The pre-1.19 era ships older Loader
            // (0.14.x) and FLK (1.9.x) than the 1.21.x baseline, so the runtime `depends`
            // floor must drop for those versions or the mod would refuse to load. Defaults
            // (>=0.16 loader, >=1.13.0 FLK) keep the existing 18 versions byte-for-byte
            // identical; each pre-1.19 version pins a lower floor via mod.* overrides.
            "loader_dep" to (project.findProperty("mod.loader_dep") ?: ">=0.16"),
            "flk_dep" to (project.findProperty("mod.flk_dep") ?: ">=1.13.0"),
        )
        props.forEach { (k, v) -> inputs.property(k, v) }
        filesMatching("fabric.mod.json") { expand(props) }
    }
}
