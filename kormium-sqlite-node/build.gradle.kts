plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

kotlin {
    // A SQLite engine for Kotlin running on Node, over the synchronous better-sqlite3 package.
    // Node only (no browser); nodejs() also runs the tests against a real on-disk/in-memory SQLite.
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
                // Reuse the shared, pure SqliteDialect (no duplication) — see ADR 0001.
                implementation(project(":kormium-sqlite-dialect"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
                // better-sqlite3: the de-facto synchronous SQLite for Node. https://github.com/WiseLibs/better-sqlite3
                implementation(npm("better-sqlite3", "12.11.1"))
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
