@file:Suppress("DEPRECATION") // legacy custom-named native targets (e.g. macosX64("native"))

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization") version "2.4.10"
}

group = "io.github.kormium.samples.ktordi"
version = "1.0"

repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.jetbrains.space/public/p/ktor/eap")
    }
}

val ktorVersion = "3.5.2"

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
                    entryPoint = "io.github.kormium.samples.ktordi.main"
                }
            }
        }
    }

    jvmToolchain(21)
    jvm {
        binaries {
            executable {
                mainClass.set("io.github.kormium.samples.ktordi.MainKt")
            }
        }
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("io.ktor:ktor-server-core:$ktorVersion")
                implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
                implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
                implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

                implementation(project(":kormium-postgres"))
                // Reified call.transaction(Catalog){} resolving Database<G> from Ktor's built-in DI.
                implementation(project(":kormium-ktor-di"))
            }
        }
        val commonTest by getting {
            dependencies { implementation(kotlin("test")) }
        }
        val jvmMain by getting {
            dependencies {
                implementation("io.ktor:ktor-server-netty:$ktorVersion")
                implementation("org.slf4j:slf4j-simple:2.0.18")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation("io.ktor:ktor-server-test-host:$ktorVersion")
                implementation("org.testcontainers:postgresql:1.21.4")
                implementation("org.postgresql:postgresql:42.7.13")
            }
        }
        if (!hostOs.contains("windows", ignoreCase = true)) {
            val nativeMain by getting {
                dependencies {
                    implementation("io.ktor:ktor-server-cio:$ktorVersion")
                }
            }
        }
    }
}
