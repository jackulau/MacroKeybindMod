plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Pure JVM — no Minecraft. Builds and tests without any MC/Fabric download.
// Target Java 8 so the shaded engine jar is consumable by EVERY Fabric variant —
// 1.16.5 (Java 8), 1.17.1 (Java 16), 1.18–1.20.4 (Java 17), 1.20.5+ (Java 21). Gradle's
// variant-aware resolution refuses a newer-Java library on an older-Java consumer, so the
// engine must target the OLDEST runtime in the matrix; 8 bytecode runs on all newer JVMs.
// The engine uses only Kotlin stdlib + java.util (TreeMap/PriorityQueue) — no Java 9+ APIs,
// so this is behavior-neutral.
kotlin {
    jvmToolchain(8)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
