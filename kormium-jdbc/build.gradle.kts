plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

kotlin {
    explicitApi()

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
                implementation("com.zaxxer:HikariCP:7.1.0")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
