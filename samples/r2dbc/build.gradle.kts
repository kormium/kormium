plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization") version "2.4.10"
}

group = "io.github.kormium.samples.r2dbc"
version = "1.0"

repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.jetbrains.space/public/p/ktor/eap")
    }
}

val ktorVersion = "3.5.2"

// JVM-only: r2dbc (and kormium-r2dbc) are JVM-only, so this sample has no native target.
kotlin {
    jvmToolchain(21)
    jvm {
        binaries {
            executable {
                mainClass.set("io.github.kormium.samples.r2dbc.MainKt")
            }
        }
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation("io.ktor:ktor-server-core:$ktorVersion")
                implementation("io.ktor:ktor-server-netty:$ktorVersion")
                implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
                implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
                implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                implementation("org.slf4j:slf4j-simple:2.0.18")

                // The async (non-blocking) Postgres backend...
                implementation(project(":kormium-r2dbc"))
                // ...used through the SAME ktor-di helpers as the blocking sample — the routes
                // are identical; only the registered driver differs.
                implementation(project(":kormium-ktor-di"))
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("io.ktor:ktor-server-test-host:$ktorVersion")
                implementation("org.testcontainers:postgresql:1.21.4")
            }
        }
    }
}
