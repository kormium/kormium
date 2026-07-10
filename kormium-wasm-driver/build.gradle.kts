plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

// Shared building blocks for the Kotlin/Wasm database engines (sqlite-wasm, sqlite-node,
// postgres-node, mysql-node): the named-parameter parser, the text-based ResultSet and the
// param-binding helper. Driver-specific externals stay in each engine; this is the common layer
// so that one fix lands everywhere instead of in four copies.
kotlin {
    explicitApi()

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
                api(project(":kormium-core"))
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
            }
        }
        val wasmJsTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
