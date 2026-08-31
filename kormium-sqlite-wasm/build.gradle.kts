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
                // The extension SPI (SqliteOptions / SuspendSqliteConnectionScope) — ADR 0013.
                api(project(":kormium-sqlite-spi"))
                // Shared Wasm driver layer: named-param parser, text ResultSet, binding helper.
                implementation(project(":kormium-wasm-driver"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
                // wa-sqlite: SQLite in WASM with async VFS (IndexedDB-capable). Taken from the
                // GitHub tag, not npm: upstream stopped publishing there after 1.0.0 (January 2024)
                // while development continued, so npm's "latest" carries SQLite 3.44.0 against
                // v1.1.2's 3.53.0. The repository commits its dist/ build, so this needs no install
                // script; yarn pins the exact commit in kotlin-js-store.
                // https://github.com/rhashimoto/wa-sqlite
                implementation(npm("wa-sqlite", "github:rhashimoto/wa-sqlite#v1.1.2"))
                // The Worker-hosted engines (createWorkerSqliteWasmDatabase and the pooled
                // createPooledSqliteWasmDatabase) are built on this instead — Kotlin/Wasm bindings
                // for the official @sqlite.org/sqlite-wasm. https://github.com/kormium/sqlite-wasm-kt
                implementation("io.github.kormium:sqlite-wasm-kt:0.1.0")
                // Those engines' Worker bundle — a standalone executable built by
                // sqlite-wasm-kt-worker, referenced as an npm dependency so webpack auto-bundles it
                // (see PoolWorkerApi.kt).
                implementation(npm("@kormium/sqlite-wasm-worker", "0.1.0"))
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
