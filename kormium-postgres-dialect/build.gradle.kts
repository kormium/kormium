plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

// The pure Postgres dialect (SQL rendering only — no driver, no `expect`). Split out of
// kormium-postgres so it can compile to every target, including js/wasm, where the libpq/JDBC
// driver (and its `expect createDatabase`) cannot go. The platform driver (kormium-postgres) and
// any web engine (PGlite, node-postgres) reuse this one copy. Targets mirror the driver's reach
// (jvm + the four native targets) plus the web stack.
kotlin {
    explicitApi()

    jvmToolchain(21)

    jvm()

    linuxX64()
    macosX64()
    macosArm64()
    mingwX64()

    js { nodejs() }
    wasmJs { nodejs() }
    wasmWasi { nodejs() }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Only the Dialect SPI comes from core; core stays unaware of concrete dialects.
                api(project(":kormium-core"))
                // PostgresDialect casts a JsonElement bind to ::jsonb, so it needs the type.
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
