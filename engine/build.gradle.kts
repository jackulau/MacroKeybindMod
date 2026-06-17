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
// Target Java 17 (not 21) so the shaded engine jar is consumable by EVERY Fabric variant:
// the 1.19.x / 1.20.1–1.20.4 variants run on Java 17, and Gradle's variant-aware resolution
// refuses a Java-21 library on a Java-17 consumer. 17 bytecode runs fine on the Java-21
// variants too. The engine uses only Kotlin stdlib (kotlin.collections.ArrayDeque.addFirst
// etc.), no Java-21-only APIs, so the downgrade is behavior-neutral.
kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
