// Root build script. Declares shared plugin *versions* once (with `apply false`) so that
// subprojects can apply them without repeating versions — required by Gradle when the same
// plugin (here Kotlin) is used by more than one subproject.
plugins {
    kotlin("jvm") version "2.1.0" apply false
}
