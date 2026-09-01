pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "kormium"
include("kormium-core", "kormium-decimal", "kormium-postgres", "kormium-postgres-dialect", "kormium-mysql", "kormium-mysql-dialect", "kormium-jdbc", "kormium-sqlite", "kormium-sqlite-dialect", "kormium-sqlite-spi", "kormium-sqlite-android-ext", "kormium-wasm-driver", "kormium-sqlite-wasm", "kormium-sqlite-js", "kormium-sqlite-node", "kormium-postgres-node", "kormium-mysql-node", "kormium-r2dbc", "benchmarks")
include("kormium-observe")
include("kormium-migrate")
include("kormium-ktor", "kormium-ktor-di", "kormium-ktor-koin")
include("kormium-bom")
include(
    "samples:ktor-di",
    "samples:ktor-koin",
    "samples:crud-sqlite",
    "samples:sqlite-vec",
    "samples:sqlite-uuid",
    "samples:repository",
    "samples:sharding",
    "samples:sqlite-cache",
    "samples:cross-instance-cache",
    "samples:r2dbc",
    "samples:wasm-todo",
)
