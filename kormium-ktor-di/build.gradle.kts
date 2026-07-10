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
        namespace = "io.github.kormium.ktor.di"
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
                // Reified transaction helpers that resolve Database<G> from Ktor's built-in DI.
                api(project(":kormium-ktor"))
                api("io.ktor:ktor-server-di:$ktorVersion")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
