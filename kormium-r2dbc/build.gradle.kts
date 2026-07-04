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
                // The r2dbc driver returns core's ResultSet and implements core's
                // SuspendDatabase, so :kormium-core is part of the public API.
                api(project(":kormium-core"))
                // PostgresDialect (the ::uuid cast) is reused verbatim for SQL rendering.
                api(project(":kormium-postgres"))
                // MySqlDialect + MySqlJvmTypeMapper for the MySQL r2dbc factory.
                api(project(":kormium-mysql"))
                implementation("org.postgresql:r2dbc-postgresql:1.0.7.RELEASE")
                implementation("io.asyncer:r2dbc-mysql:1.3.0")
                implementation("io.r2dbc:r2dbc-pool:1.0.2.RELEASE")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
                implementation("org.testcontainers:postgresql:1.20.4")
                implementation("org.testcontainers:mysql:1.20.4")
            }
        }
    }
}
