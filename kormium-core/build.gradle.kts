plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization") version "2.4.0"
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

    // Compose Multiplatform targets. The Android target is configured via the AGP KMP
    // library plugin's androidLibrary DSL (AGP 9 dropped com.android.library + androidTarget()).
    android {
        namespace = "io.github.kormium"
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

    // Kotlin/JS + Kotlin/Wasm web stack. nodejs() is enough to compile the klib and run
    // tests; consumers can still use the artifact in the browser (the environment only
    // affects test/run tasks, not the produced klib). browser() is added per-engine
    // (e.g. kormium-pglite) where a browser demo is actually run.
    js { nodejs() }
    wasmJs { nodejs() }
    wasmWasi { nodejs() }

    // Optimized test binary (linkBenchReleaseTest<Target>) for CPU micro-benchmarks: the default
    // debug test kexe is unoptimized K/N code and misrepresents CPU-bound throughput by 2-3x.
    // Linked only when explicitly requested, so regular test/CI builds don't pay for it.
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.test("bench", listOf(org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE))
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Public suspend API (suspendTransaction/suspendAutocommit) is coroutine-based.
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
                // LocalDate/LocalTime/LocalDateTime appear in the public API (ColumnType,
                // ResultSet), so consumers need the types on their compile classpath.
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }
        // Logging is reached through the internal KormiumLogger facade. Every target EXCEPT
        // wasmWasi delegates to kotlin-logging, which has no wasmWasi artifact (7.0.3);
        // wasmWasi gets a no-op actual. So kotlin-logging lives here, not in commonMain.
        // See src/loggingMain/.../KormiumLogger.logging.kt and src/wasmWasiMain/.../KormiumLogger.wasmWasi.kt.
        val loggingMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation("io.github.oshai:kotlin-logging:7.0.3")
            }
        }
        val jvmMain by getting {
            dependsOn(loggingMain)
            dependencies {
                // kotlin-logging delegates to SLF4J on the JVM; core needs the API on the
                // runtime classpath (previously pulled in transitively via the drivers).
                implementation("org.slf4j:slf4j-api:2.0.16")
            }
        }
        val androidMain by getting {
            dependsOn(loggingMain)
            dependencies {
                // Android is JVM-flavoured: kotlin-logging delegates to SLF4J here too.
                implementation("org.slf4j:slf4j-api:2.0.16")
            }
        }
        // Native + JS + Wasm/JS all have kotlin-logging artifacts -> share loggingMain.
        // wasmWasiMain deliberately stays on commonMain only.
        val nativeMain by getting { dependsOn(loggingMain) }
        val jsMain by getting { dependsOn(loggingMain) }
        val wasmJsMain by getting { dependsOn(loggingMain) }
    }
}
