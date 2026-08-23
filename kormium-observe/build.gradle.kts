plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

repositories {
    google()
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.jetbrains.space/public/p/ktor/eap")
    }
}

kotlin {
    explicitApi()

    jvmToolchain(21)

    jvm {
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    // Compose Multiplatform targets (AGP KMP library plugin's androidLibrary DSL).
    android {
        namespace = "io.github.kormium.observe"
        compileSdk = 36
        minSdk = 24
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    linuxX64()
    macosX64()
    macosArm64()
    mingwX64()

    // Kotlin/JS + Kotlin/Wasm: change observation is pure coroutines Flow over core's WriteListener
    // seam, so it compiles to the web stack too.
    js { nodejs() }
    wasmJs { nodejs() }
    wasmWasi { nodejs() }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Reactive change observation built on core's WriteListener seam. Table/Catalog/
                // SuspendDatabase appear in the public Flow signatures, so :kormium-core is api;
                // Flow itself comes transitively from kormium-core's api coroutines dependency.
                api(project(":kormium-core"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            }
        }
    }
}
