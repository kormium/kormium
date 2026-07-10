plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

// The pure MySQL/MariaDB dialect (SQL rendering only — no driver, no `expect`). Split out of
// kormium-mysql so it can compile to every target, including js/wasm. The platform driver
// (kormium-mysql) and any web engine (node mysql2) reuse this one copy. Targets mirror the
// driver's reach (jvm + the three unix native targets; no mingw — Windows uses the JVM driver)
// plus the web stack.
kotlin {
    explicitApi()

    jvmToolchain(21)

    jvm()

    linuxX64()
    macosX64()
    macosArm64()

    js { nodejs() }
    wasmJs { nodejs() }
    wasmWasi { nodejs() }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Only the Dialect SPI comes from core; core stays unaware of concrete dialects.
                api(project(":kormium-core"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
