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
            // Long-running stability/soak tests are opt-in: run with -Pstability.
            useJUnitPlatform {
                if (!project.hasProperty("stability")) excludeTags("stability")
            }
        }
    }

    val isWindowsHost = System.getProperty("os.name").startsWith("Windows")
    // The libpq artifact Windows test binaries link against, preferring the dynamic
    // library: import lib, then the DLL itself (lld links DLLs directly), then the MSVC
    // import lib. -Plibpq.lib=<dir> (set by benchmarks/run.bat) takes precedence over
    // the known MSYS2 / EDB install locations.
    val windowsLibpqArtifact: String? by lazy {
        val roots = listOfNotNull((findProperty("libpq.lib") as String?)?.let { file(it).parentFile }) +
            listOf(file("C:/msys64/mingw64")) +
            (18 downTo 14).map { file("${System.getenv("ProgramFiles") ?: "C:/Program Files"}/PostgreSQL/$it") }
        roots.firstNotNullOfOrNull { root ->
            listOf("lib/libpq.dll.a", "bin/libpq.dll", "lib/libpq.lib")
                .map(root::resolve).firstOrNull { it.isFile }
        }?.absolutePath?.replace('\\', '/')
    }

    // The native Postgres driver talks to libpq via cinterop (formerly the :pgkn module,
    // now folded into this module's nativeMain). Register the same-named "libpq" cinterop
    // on every native target so it commonizes into the shared nativeMain source set.
    listOf(linuxX64(), macosX64(), macosArm64(), mingwX64()).forEach { target ->
        target.compilations.getByName("main").cinterops {
            register("libpq") {
                defFile(project.file("src/nativeInterop/cinterop/libpq.def"))
                // Non-standard libpq locations (mainly custom Windows installs): pass
                // -Plibpq.include=<dir> and -Plibpq.lib=<dir>. benchmarks/run.bat fills
                // these in automatically from pg_config / known install paths.
                (findProperty("libpq.include") as String?)?.let { compilerOpts("-I$it") }
            }
            // winsock2 bridge for the Windows async reactor (WSAPoll). A direct cinterop on the
            // system <winsock2.h> yields an empty binding package, so we cinterop a project-owned
            // shim header (winsock_shim.h) that wraps the calls — see WINDOWS_ASYNC_NOTES.md.
            // Only the mingw target registers it.
            if (target.name == "mingwX64") {
                register("winsock") {
                    defFile(project.file("src/nativeInterop/cinterop/winsock.def"))
                    includeDirs(project.file("src/nativeInterop/cinterop"))
                }
            }
        }
        // cinterop ignores linker options; libpq is supplied when test binaries link.
        // On Windows the artifact is chosen explicitly (see windowsLibpqArtifact): a bare
        // -L/-lpq can pick the static libpq.a, whose bundled pthread shim duplicates
        // Kotlin/Native's libwinpthread symbols.
        if (target.name == "mingwX64" && isWindowsHost) {
            target.binaries.all {
                windowsLibpqArtifact?.let { linkerOpts(it) } ?: logger.warn(
                    "kormium-postgres: no Windows libpq found; install MSYS2 " +
                        "mingw-w64-x86_64-postgresql or pass -Plibpq.lib=<dir>",
                )
            }
        } else {
            (findProperty("libpq.lib") as String?)?.let { dir ->
                target.binaries.all { linkerOpts("-L$dir") }
            }
        }
        // Optimized test binary (linkBenchReleaseTest<Target>) for benchmarks/run.sh: the default
        // debug test kexe is unoptimized K/N code and misrepresents CPU-bound throughput by
        // 2-3x. Linked only when explicitly requested, so regular test/CI builds don't pay for it.
        target.binaries.test("bench", listOf(org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE))
    }
    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Exposes createDatabase(...) plus the PostgresDriver interface (formerly the :pg
                // module). PostgresDriver is part of the public return type, so :kormium-core is an
                // api dependency.
                api(project(":kormium-core"))
                // PostgresDialect lives in the pure dialect module (split out so it can also
                // compile to js/wasm). `api` keeps `io.github.kormium.PostgresDialect` visible to
                // existing consumers exactly as before.
                api(project(":kormium-postgres-dialect"))
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
                implementation("io.github.oshai:kotlin-logging:7.0.3")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("com.ionspin.kotlin:bignum:0.3.10")
            }
        }
        val jvmMain by getting {
            dependencies {
                // The JVM Postgres driver is the shared JDBC driver wired with the
                // pgjdbc URL + PgResultSetWrapper (which uses kotlinx-datetime).
                implementation(project(":kormium-jdbc"))
                implementation("org.postgresql:postgresql:42.7.4")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
                // PostgresJvmTypeMapper converts ionspin BigDecimal to java.math.BigDecimal.
                implementation("com.ionspin.kotlin:bignum:0.3.10")
            }
        }
        val jvmTest by getting {
            dependencies {
                // End-to-end tests of the JVM driver against a real Postgres in Docker.
                implementation(project(":kormium-migrate"))
                implementation("org.testcontainers:postgresql:1.20.4")
                implementation("org.postgresql:postgresql:42.7.4")
                // For the all-column-types round-trip test (Instant / Json columns).
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            }
        }
        val nativeMain by getting {
            dependencies {
                // The native libpq driver (formerly :pgkn).
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("io.github.oshai:kotlin-logging:7.0.3")
            }
        }
        // The async socket reactor's syscalls differ by OS: poll/pipe/fcntl on Unix
        // (UnixSocketReactor), WSAPoll on Windows (mingwX64Main), shared logic in nativeMain.
        // The Unix actual is shared across the three unix leaf targets via a srcDir rather than
        // a common (commonized) intermediate source set: posix types like nfds_t differ in bit
        // width between linux and macos, which the K/N commonizer rejects in shared metadata.
        // Compiling the file in each leaf against its own posix sidesteps that — one file on disk,
        // no metadata compilation of platform code.
        listOf("linuxX64", "macosX64", "macosArm64").forEach { target ->
            getByName("${target}Main").kotlin.srcDir("src/unixShared/kotlin")
            getByName("${target}Test").kotlin.srcDir("src/unixSharedTest/kotlin")
        }
    }
}
