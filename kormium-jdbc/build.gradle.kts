plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)

    jvm {
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    sourceSets {
        val jvmMain by getting {
            dependencies {
                // The generic JDBC driver returns core's ResultSet and binds core's
                // SqlParameterSource, so :kormium-core is part of the public API.
                api(project(":kormium-core"))
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
                implementation("com.zaxxer:HikariCP:6.2.1")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
