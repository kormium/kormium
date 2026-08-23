@file:Suppress("DEPRECATION") // legacy custom-named native target (e.g. macosX64("native"))

plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

group = "io.github.kormium.samples.sqlitecache"
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
                    entryPoint = "io.github.kormium.samples.sqlitecache.main"
                }
            }
        }
    }

    jvmToolchain(21)
    jvm {
        binaries {
            executable {
                mainClass.set("io.github.kormium.samples.sqlitecache.MainKt")
            }
        }
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Postgres = source of truth; SQLite = local read-through cache.
                implementation(project(":kormium-postgres"))
                implementation(project(":kormium-sqlite"))
            }
        }
        val commonTest by getting {
            dependencies { implementation(kotlin("test")) }
        }
        val jvmTest by getting {
            dependencies {
                implementation("org.testcontainers:postgresql:1.21.4")
                implementation("org.postgresql:postgresql:42.7.13")
            }
        }
        // The native Postgres driver now ships inside :kormium-postgres (nativeMain).
    }
}
