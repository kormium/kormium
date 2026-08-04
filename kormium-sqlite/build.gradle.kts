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

    // Compose Multiplatform: Android can't use the Kotlin/Native sqlite3 cinterop (it
    // compiles to JVM bytecode), so it gets its own driver in androidMain on top of
    // androidx.sqlite's bundled SQLite. iOS reuses the cinterop driver below.
    android {
        namespace = "io.github.kormium.sqlite"
        compileSdk = 36
        minSdk = 24
    }

    // SQLite is built from the vendored amalgamation (sqlite3.c) and embedded as a static
    // library, rather than linked from the system. The system libsqlite3 on a modern Linux
    // distro targets a newer glibc than Kotlin/Native's bundled glibc-2.19 sysroot, so the
    // linker rejects it (undefined GLIBC_2.3x symbol versions) — see sqlite3.def. Compiling
    // the amalgamation with the K/N toolchain keeps it ABI-compatible and self-contained.
    val cinteropDir = project.file("src/nativeInterop/cinterop")
    val sqliteAmalgamation = cinteropDir.resolve("sqlite3.c")
    // run_konan resolves the host-appropriate clang/llvm-ar and the per-target sysroot, so
    // we don't hand-maintain cross-compilation flags. Locate the toolchain matching the KGP
    // version lazily (it may still be downloading at configuration time).
    val konanDataDir = System.getenv("KONAN_DATA_DIR")?.let(::File)
        ?: File(System.getProperty("user.home"), ".konan")
    val konanVersion = "2.4.0"
    // On Windows the distribution ships run_konan.bat, which has to go through cmd /c.
    fun runKonan(): List<String> {
        val dist = (konanDataDir.listFiles { f ->
            f.isDirectory && f.name.startsWith("kotlin-native-prebuilt-") && f.name.endsWith(konanVersion)
        } ?: emptyArray<File>()).firstOrNull()
            ?: error("Kotlin/Native $konanVersion toolchain not found under $konanDataDir")
        return if (System.getProperty("os.name").startsWith("Windows"))
            listOf("cmd", "/c", dist.resolve("bin/run_konan.bat").absolutePath)
        else
            listOf(dist.resolve("bin/run_konan").absolutePath)
    }
    val sqliteDefines = listOf(
        "-O2",
        "-DSQLITE_THREADSAFE=1",
        "-DSQLITE_ENABLE_FTS5=1",
        "-DSQLITE_ENABLE_RTREE=1",
        "-DSQLITE_ENABLE_DBSTAT_VTAB=1",
        "-DSQLITE_ENABLE_COLUMN_METADATA=1",
    )

    // Register the same-named "sqlite3" cinterop on every native target so it commonizes
    // into the shared nativeMain source set.
    listOf(
        linuxX64(), macosX64(), macosArm64(), mingwX64(),
        iosX64(), iosArm64(), iosSimulatorArm64(),
    ).forEach { target ->
        val konanName = target.konanTarget.name // e.g. linux_x64, macos_arm64, ios_arm64
        val capName = target.targetName.replaceFirstChar { it.uppercase() }
        val outDir = layout.buildDirectory.dir("sqlite3/$konanName")
        val objFile = outDir.map { it.file("sqlite3.o") }
        val staticLib = outDir.map { it.file("libsqlite3.a") }

        val compileSqlite = tasks.register<Exec>("compileSqlite3$capName") {
            description = "Compiles the vendored SQLite amalgamation for $konanName"
            inputs.file(sqliteAmalgamation)
            inputs.property("defines", sqliteDefines)
            outputs.file(objFile)
            doFirst {
                objFile.get().asFile.parentFile.mkdirs()
                // The compiler arguments go through a clang response file: on Windows the
                // run_konan.bat hop strips `=` from arguments (cmd argument splitting), which
                // silently destroyed the -DSQLITE_*=1 defines. Forward slashes keep the paths
                // out of response-file escaping trouble.
                fun q(f: File) = "\"" + f.absolutePath.replace('\\', '/') + "\""
                val rsp = objFile.get().asFile.resolveSibling("clang-args.rsp")
                rsp.writeText(
                    (sqliteDefines + listOf("-c", q(sqliteAmalgamation), "-o", q(objFile.get().asFile)))
                        .joinToString("\n"),
                )
                commandLine(runKonan() + listOf("clang", "clang", konanName, "@" + rsp.absolutePath))
            }
        }
        val archiveSqlite = tasks.register<Exec>("archiveSqlite3$capName") {
            description = "Archives the SQLite object into a static library for $konanName"
            dependsOn(compileSqlite)
            inputs.file(objFile)
            outputs.file(staticLib)
            doFirst {
                commandLine(
                    runKonan() + listOf(
                        "llvm", "llvm-ar", "rcs",
                        staticLib.get().asFile.absolutePath, objFile.get().asFile.absolutePath,
                    ),
                )
            }
        }

        target.compilations.getByName("main").cinterops {
            register("sqlite3") {
                defFile(cinteropDir.resolve("sqlite3.def"))
                packageName("csqlite")
                // -I finds the vendored sqlite3.h; the static lib (built above) supplies the
                // implementation and is embedded into the cinterop klib.
                compilerOpts("-I$cinteropDir")
                extraOpts("-staticLibrary", "libsqlite3.a", "-libraryPath", outDir.get().asFile.absolutePath)
            }
        }
        tasks.named("cinteropSqlite3$capName").configure { dependsOn(archiveSqlite) }
    }
    // Optimized test binary (linkBenchReleaseTest<Target>) for SqliteE2EBench: the default debug
    // test kexe is unoptimized K/N code and misrepresents CPU-bound throughput by ~10x. Linked
    // only when explicitly requested, so regular test and CI builds don't pay for it.
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.test("bench", listOf(org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE))
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Exposes SqliteDriver / createSqliteDatabase. The driver returns core's
                // ResultSet and binds core's SqlParameterSource, so :kormium-core is part of the
                // public API.
                api(project(":kormium-core"))
                // SqliteDialect lives in the pure dialect module (split out so it can also compile
                // to js/wasm). `api` keeps `io.github.kormium.SqliteDialect` visible to existing
                // consumers exactly as before.
                api(project(":kormium-sqlite-dialect"))
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":kormium-observe"))
                implementation(project(":kormium-decimal"))
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }
        val jvmMain by getting {
            dependencies {
                // The JVM SQLite driver is the shared JDBC driver wired with the
                // sqlite-jdbc URL + SqliteResultSetWrapper.
                implementation(project(":kormium-jdbc"))
                implementation("org.xerial:sqlite-jdbc:3.47.1.0")
            }
        }
        val androidMain by getting {
            dependencies {
                // androidx.sqlite's bundled SQLite ships its own native library for Android,
                // so the driver works on-device without relying on the framework's sqlite.
                implementation("androidx.sqlite:sqlite:2.6.2")
                implementation("androidx.sqlite:sqlite-bundled:2.6.2")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            }
        }
    }
}
