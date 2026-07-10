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

val ktorVersion = "3.5.0"

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
        namespace = "io.github.kormium.ktor"
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

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // DI-agnostic ktor integration: explicit-db transaction helpers + the
                // KormException -> HttpStatusCode mapper + an optional close-on-stop plugin.
                // Database/Scope/Catalog/exceptions are part of the public API, so :kormium-core is api;
                // ApplicationCall appears in the public extension signatures, so ktor-server-core is api.
                api(project(":kormium-core"))
                api("io.ktor:ktor-server-core:$ktorVersion")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
