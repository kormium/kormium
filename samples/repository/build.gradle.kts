@file:Suppress("DEPRECATION") // legacy custom-named native target (e.g. macosX64("native"))

plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

group = "io.github.kormium.samples.repository"
version = "1.0"

kotlin {
    val hostOs = System.getProperty("os.name")
    if (!hostOs.contains("windows", ignoreCase = true)) {
        val arch = System.getProperty("os.arch")
        val nativeTarget = when {
            hostOs == "Mac OS X" && arch == "x86_64" -> macosX64("native")
            hostOs == "Mac OS X" && arch == "aarch64" -> macosArm64("native")
            hostOs == "Linux" -> linuxX64("native")
            else -> throw GradleException("Host OS is not supported in Kotlin/Native.")
        }
        nativeTarget.apply {
            binaries {
                executable {
                    entryPoint = "io.github.kormium.samples.repository.main"
                }
            }
        }
    }

    jvmToolchain(21)
    jvm {
        binaries {
            executable {
                mainClass.set("io.github.kormium.samples.repository.MainKt")
            }
        }
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // SQLite (self-contained, JVM + native) + kormium-observe for reactive Flow queries.
                implementation(project(":kormium-sqlite"))
                implementation(project(":kormium-observe"))
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
