plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

kotlin {
    // wa-sqlite is SQLite compiled to WASM, reached through JS interop, so this engine is
    // Kotlin/Wasm (JS) only. nodejs() runs the tests against an in-memory database (`:memory:`);
    // the browser uses the IndexedDB VFS for persistence (see the todo sample).
    compilerOptions {
        optIn.add("kotlin.js.ExperimentalWasmJsInterop")
    }

    wasmJs {
        browser()
        nodejs()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val wasmJsMain by getting {
            dependencies {
                // The suspend SPI from core; the public surface.
                api(project(":kormium-core"))
                // Reuse the shared, pure SqliteDialect (no duplication) — see ADR 0001.
                implementation(project(":kormium-sqlite-dialect"))
                // Shared Wasm driver layer: named-param parser, text ResultSet, binding helper.
                implementation(project(":kormium-wasm-driver"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
                // wa-sqlite: SQLite in WASM with async VFS (IndexedDB-capable). https://github.com/rhashimoto/wa-sqlite
                implementation(npm("wa-sqlite", "1.0.0"))
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
