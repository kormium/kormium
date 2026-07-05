plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

kotlin {
    explicitApi()

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
                // The pooled/OPFS engine (createPooledSqliteWasmDatabase) is built on this instead —
                // see kormium/sqlite-wasm-kt. Not yet published; substituted via includeBuild in
                // settings.gradle.kts.
                implementation("io.github.kormium:sqlite-wasm-kt:0.1.0")
                // The pool's reader/writer Worker bundle — a standalone executable built by
                // sqlite-wasm-kt-worker, referenced as an npm dependency so webpack auto-bundles it
                // (see PooledSqliteWasmDatabase.kt). DEV-ONLY local path until it's published for
                // real; run `:sqlite-wasm-kt-worker:stageNpmPackage` in ../sqlite-wasm-kt first.
                implementation(npm("@kormium/sqlite-wasm-worker", "file:/Users/sergey/Projects/sqlite-wasm-kt/sqlite-wasm-kt-worker/npm-package"))
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
