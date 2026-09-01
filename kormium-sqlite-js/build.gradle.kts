plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

// Browser SQLite for the Kotlin/JS (IR) target — the sibling of kormium-sqlite-wasm, which is
// Kotlin/Wasm only. Same wa-sqlite engine (SQLite compiled to WASM, reached through JS interop),
// but bound with Kotlin/JS interop so it links against js-only consumers such as the kotlin-react
// wrappers (which have no wasmJs artifact). Only the single-connection main-thread engine is
// ported (createSqliteJsDatabase, IndexedDB persistence); the Worker/pooled engines depend on
// wasmJs-only companion executables and stay in kormium-sqlite-wasm.
kotlin {
    explicitApi()

    js {
        // wa-sqlite ships pure ESM ("type": "module", .mjs), so the consuming/test module must
        // emit ES modules too; useEsModules() also pins the es2015 compilation target.
        useEsModules()
        browser()
        nodejs()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val jsMain by getting {
            dependencies {
                // The suspend SPI + DSL from core; the public surface.
                api(project(":kormium-core"))
                // Reuse the shared, pure SqliteDialect (no duplication) — see ADR 0001.
                implementation(project(":kormium-sqlite-dialect"))
                // The extension SPI (SqliteOptions / SuspendSqliteConnectionScope) — ADR 0013.
                api(project(":kormium-sqlite-spi"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
                // wa-sqlite: SQLite in WASM with async VFS (IndexedDB-capable). Taken from the
                // GitHub tag, not npm: upstream stopped publishing there after 1.0.0 (January 2024)
                // while development continued, so npm's "latest" carries SQLite 3.44.0 against
                // v1.1.2's 3.53.0. The repository commits its dist/ build, so this needs no install
                // script; yarn pins the exact commit in kotlin-js-store.
                // https://github.com/rhashimoto/wa-sqlite
                implementation(npm("wa-sqlite", "github:rhashimoto/wa-sqlite#v1.1.2"))
            }
        }
        val jsTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            }
        }
    }
}
