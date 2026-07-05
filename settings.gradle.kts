pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

// kormium-sqlite-wasm's pooled/OPFS engine depends on kormium/sqlite-wasm-kt, which isn't
// published yet. Any `io.github.kormium:sqlite-wasm-kt` dependency is substituted by the included
// build's project. Once it's on Maven Central, delete this line and the dependency resolves
// normally (same pattern as ../pglite's includeBuild("../korm")).
includeBuild("../sqlite-wasm-kt")

rootProject.name = "kormium"
include("kormium-core", "kormium-decimal", "kormium-postgres", "kormium-postgres-dialect", "kormium-mysql", "kormium-mysql-dialect", "kormium-jdbc", "kormium-sqlite", "kormium-sqlite-dialect", "kormium-wasm-driver", "kormium-sqlite-wasm", "kormium-sqlite-node", "kormium-postgres-node", "kormium-mysql-node", "kormium-r2dbc", "benchmarks")
include("kormium-observe")
include("kormium-migrate")
include("kormium-ktor", "kormium-ktor-di", "kormium-ktor-koin")
include("kormium-bom")
include(
    "samples:ktor-di",
    "samples:ktor-koin",
    "samples:crud-sqlite",
    "samples:repository",
    "samples:sharding",
    "samples:sqlite-cache",
    "samples:cross-instance-cache",
    "samples:r2dbc",
    "samples:wasm-todo",
)
