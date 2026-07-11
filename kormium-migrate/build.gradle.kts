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
        namespace = "io.github.kormium.migrate"
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

    // Kotlin/JS + Kotlin/Wasm: the migration runner is pure (raw SQL + CRC32 checksum + advisory
    // lock), so it compiles to the web stack like the rest of the agnostic layer.
    js { nodejs() }
    wasmJs { nodejs() }
    wasmWasi { nodejs() }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // The migration runner is built on core's Database/SqlExecutor/Dialect seam, which
                // appear in the public `migrate` / `Migration` signatures, so :kormium-core is api.
                api(project(":kormium-core"))
                // Instant for the migration journal's applied_at timestamp (internal use only).
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        // End-to-end runner tests execute against a real SQLite :memory: database. kormium-sqlite
        // has no js/wasm target, so these tests live in an intermediate set shared by jvm + native
        // (not the web test source sets, which would otherwise fail to resolve kormium-sqlite).
        val backendTest by creating {
            dependsOn(commonTest)
            dependencies {
                implementation(project(":kormium-sqlite"))
            }
        }
        val jvmTest by getting { dependsOn(backendTest) }
        val nativeTest by getting { dependsOn(backendTest) }
    }
}
