plugins {
    kotlin("multiplatform")
}

// Extensions consumed the way an application consumes them: as published coordinates.
//
// Nothing here compiles C, vendors a header or knows an entry-point name — that all lives inside
// the packages, which come from Maven Central. Kormium contributes the SPI and the one SQLite in
// the process; each package brings only its own static library. See ADR 0013.
//
// Not published: a demonstration and an integration test, not a library.
val extensionsVersion = providers.gradleProperty("sqliteExtensionsVersion").get()

kotlin {
    jvmToolchain(21)

    linuxX64()
    macosX64()
    macosArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        val nativeMain by getting {
            dependencies {
                implementation(project(":kormium-sqlite"))
                implementation("io.github.kormium:kormium-sqlite-vec:$extensionsVersion")
                implementation("io.github.kormium:kormium-sqlite-uuid:$extensionsVersion")
                implementation("io.github.kormium:kormium-sqlite-regexp:$extensionsVersion")
                implementation("io.github.kormium:kormium-sqlite-series:$extensionsVersion")
            }
        }
        val nativeTest by getting {
            dependencies { implementation(kotlin("test")) }
        }
    }
}
