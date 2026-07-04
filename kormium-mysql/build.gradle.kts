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

    // The native MySQL driver talks to a MySQL C client (MariaDB Connector/C, libmariadb) via
    // cinterop. Register the same-named "libmysqlclient" cinterop on every native target so it
    // commonizes into the shared nativeMain source set. Unlike libpq there is no portable
    // non-blocking API, so the suspend path falls back to the core blocking offload.
    //
    // Unix-only: the MYSQL_BIND buffers use `unsigned long` lengths, which are 64-bit on the
    // LP64 unix targets but 32-bit on Windows LLP64 — the K/N commonizer rejects that mismatch in
    // shared nativeMain metadata. Windows is served by the JVM (JDBC) driver. mingwX64 is therefore
    // intentionally absent here.
    listOf(linuxX64(), macosX64(), macosArm64()).forEach { target ->
        target.compilations.getByName("main").cinterops {
            register("libmysqlclient") {
                defFile(project.file("src/nativeInterop/cinterop/libmysqlclient.def"))
                // Non-standard client locations: pass -Pmysql.include=<dir> (and -Pmysql.lib=<dir>
                // for the link step below).
                (findProperty("mysql.include") as String?)?.let { compilerOpts("-I$it") }
            }
        }
        (findProperty("mysql.lib") as String?)?.let { dir ->
            target.binaries.all { linkerOpts("-L$dir") }
        }
    }
    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Exposes createDatabase(...) plus the MySQL Dialect + MySqlDriver interface.
                // MySqlDriver is part of the public return type, so :kormium-core is an api dependency.
                api(project(":kormium-core"))
                // MySqlDialect lives in the pure dialect module (split out so it can also compile
                // to js/wasm). `api` keeps `io.github.kormium.MySqlDialect` visible to existing
                // consumers exactly as before.
                api(project(":kormium-mysql-dialect"))
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
                // MySqlJvmTypeMapper binds a JsonElement as its text form into a JSON column.
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":kormium-decimal"))
            }
        }
        val jvmMain by getting {
            dependencies {
                // The JVM MySQL driver is the shared JDBC driver wired with the mysql-connector-j
                // URL + MySqlResultSetWrapper (which uses kotlinx-datetime).
                implementation(project(":kormium-jdbc"))
                implementation("com.mysql:mysql-connector-j:8.4.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
            }
        }
        val jvmTest by getting {
            dependencies {
                // End-to-end tests of the JVM driver against a real MySQL in Docker.
                implementation(project(":kormium-migrate"))
                implementation("org.testcontainers:mysql:1.20.4")
                implementation("com.mysql:mysql-connector-j:8.4.0")
                // For the all-column-types round-trip test (Instant / Json columns).
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            }
        }
        val nativeMain by getting {
            dependencies {
                // The native libmysqlclient driver.
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        }
        val nativeTest by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            }
        }
    }
}
