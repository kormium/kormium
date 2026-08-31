plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

repositories {
    google()
    mavenCentral()
}

// The extension SPI: the contract between Kormium's SQLite engines and third-party extension
// packages (sqlite-vec and friends), which live in their own repository. It is a module of its
// own — rather than a few types inside kormium-sqlite — for two reasons.
//
// 1. Reach. The engines do not share a target set: kormium-sqlite is jvm/android/native,
//    kormium-sqlite-node and -wasm are wasmJs-only, kormium-sqlite-js is js-only. An extension
//    package wants ONE `SqliteVec` object in commonMain, so it needs a dependency that exists on
//    every target. This module and kormium-sqlite-dialect are the only ones that do.
// 2. Stability. Its version is a compatibility contract for an ecosystem Kormium does not
//    control, so it must not be welded to a module that keeps changing (the dialect does).
//
// See ADR 0013. Keep this module tiny and slow-moving on purpose.
kotlin {
    explicitApi()

    jvmToolchain(21)

    jvm()

    android {
        namespace = "io.github.kormium.sqlite.spi"
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
                // For KormiumException and the @KormiumDsl marker. core compiles to every target
                // this module does, so it costs nothing in reach.
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
