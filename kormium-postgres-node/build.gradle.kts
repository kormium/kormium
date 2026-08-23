plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

kotlin {
    explicitApi()

    // A Postgres engine for Kotlin running on Node, over the async node-postgres (`pg`) package
    // talking to a real Postgres server. Node only.
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
                // Reuse the shared, pure PostgresDialect — see ADR 0001.
                implementation(project(":kormium-postgres-dialect"))
                // Shared Wasm driver layer: named-param parser, text ResultSet, binding helper.
                implementation(project(":kormium-wasm-driver"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
                // node-postgres: pure-JS Postgres client. https://node-postgres.com
                implementation(npm("pg", "8.13.1"))
            }
        }
        val wasmJsTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            }
        }
    }
}
