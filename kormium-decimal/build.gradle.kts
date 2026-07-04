plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

repositories {
    google()
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

    // Compose Multiplatform targets (AGP KMP library plugin's androidLibrary DSL).
    android {
        namespace = "io.github.kormium.decimal"
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

    js { nodejs() }
    wasmJs { nodejs() }
    wasmWasi { nodejs() }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Column/ColumnType/ResultSet appear in the public API.
                api(project(":kormium-core"))
                // Decimal is the column's value type.
                api("io.github.kormium:decimal:0.1.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }
        // JVM-flavoured targets bind java.math.BigDecimal so JDBC/r2dbc drivers declare the
        // real numeric parameter type; everything else binds decimal text.
        val jvmSharedMain by creating {
            dependsOn(commonMain)
        }
        val jvmMain by getting { dependsOn(jvmSharedMain) }
        val androidMain by getting { dependsOn(jvmSharedMain) }
        val textParamMain by creating {
            dependsOn(commonMain)
        }
        val nativeMain by getting { dependsOn(textParamMain) }
        val jsMain by getting { dependsOn(textParamMain) }
        val wasmJsMain by getting { dependsOn(textParamMain) }
        val wasmWasiMain by getting { dependsOn(textParamMain) }
    }
}
