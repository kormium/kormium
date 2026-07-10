plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

repositories {
    google()
    mavenCentral()
}

// The pure SQLite dialect (SQL rendering only — no driver, no `expect`). It is split out of
// kormium-sqlite so it can compile to EVERY target, including js/wasm, where the JDBC/native
// driver (and its `expect createSqliteDatabase`) cannot go. Both the platform driver
// (kormium-sqlite) and any web engine (wa-sqlite, node:sqlite) reuse this one copy.
kotlin {
    explicitApi()

    jvmToolchain(21)

    jvm()

    android {
        namespace = "io.github.kormium.sqlite.dialect"
        compileSdk = 36
        minSdk = 24
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    linuxX64()
    macosX64()
    macosArm64()
    mingwX64()

    js { nodejs() }
    wasmJs { nodejs() }
    wasmWasi { nodejs() }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Only the Dialect SPI (interface) comes from core; nothing dialect-specific
                // lives there. core stays unaware of concrete dialects.
                api(project(":kormium-core"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
