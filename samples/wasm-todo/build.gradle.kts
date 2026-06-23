plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

repositories {
    google()
    mavenCentral()
}

// kotlinx-datetime 0.6.2's `Instant` typealias double-binds against Kotlin 2.4's stable
// kotlin.time.Instant during the wasm *executable* link ("IrTypeAliasSymbolImpl is already bound").
// The `-0.6.x-compat` build keeps the 0.6 API without that bridge and is ABI-compatible, so forcing
// it on this sample's link classpath fixes the binary without touching core/engine modules.
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0-0.6.x-compat")
    }
}

kotlin {
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "todo.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val wasmJsMain by getting {
            dependencies {
                implementation(project(":kormium-core"))
                implementation(project(":kormium-sqlite-wasm"))

                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)

                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
                implementation("org.jetbrains.kotlinx:kotlinx-browser:0.3")
            }
        }
    }
}
