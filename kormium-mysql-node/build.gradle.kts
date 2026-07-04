plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

kotlin {
    explicitApi()

    // A MySQL/MariaDB engine for Kotlin running on Node, over the async mysql2 package talking to a
    // real server. Node only.
    compilerOptions {
        optIn.add("kotlin.js.ExperimentalWasmJsInterop")
    }

    wasmJs {
        nodejs()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val wasmJsMain by getting {
            dependencies {
                api(project(":kormium-core"))
                // Reuse the shared, pure MySqlDialect — see ADR 0001.
                implementation(project(":kormium-mysql-dialect"))
                // Shared Wasm driver layer: named-param parser, text ResultSet, binding helper.
                implementation(project(":kormium-wasm-driver"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
                // mysql2: pure-JS MySQL/MariaDB client with a promise API. https://sidorares.github.io/node-mysql2
                implementation(npm("mysql2", "3.22.5"))
            }
        }
        val wasmJsTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }
    }
}
