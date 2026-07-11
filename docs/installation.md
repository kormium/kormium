# Installation

Kormium is published to Maven Central under the group `io.github.kormium`.

The recommended Gradle setup is to import `kormium-bom` once and then declare artifacts
without versions:

```kotlin
dependencies {
    implementation(platform("io.github.kormium:kormium-bom:<version>"))

    implementation("io.github.kormium:kormium-postgres")
    // or:
    implementation("io.github.kormium:kormium-sqlite")
    // or, JVM-only true async PostgreSQL:
    implementation("io.github.kormium:kormium-r2dbc")
}
```

Without the BOM, put the same version on every artifact:

```kotlin
dependencies {
    implementation("io.github.kormium:kormium-postgres:<version>")
    implementation("io.github.kormium:kormium-ktor:<version>")
}
```

## Requirements

- JDK 21 or newer for JVM builds.
- Kotlin Multiplatform project setup if you use Native, Android or iOS targets.
- PostgreSQL client libraries for the Native PostgreSQL driver.
- SQLite headers for Native SQLite on Linux if your distribution does not install them by
  default.

## Artifacts

| Artifact | Add when |
| --- | --- |
| [`kormium-core`](../kormium-core/README.md) | You implement a custom backend or only need the common DSL types |
| [`kormium-decimal`](../kormium-decimal/README.md) | You store exact decimals (`numeric`/`DECIMAL` columns) |
| [`kormium-postgres`](../kormium-postgres/README.md) | You use PostgreSQL through JDBC on JVM or libpq on Native |
| [`kormium-sqlite`](../kormium-sqlite/README.md) | You use SQLite on JVM, Native or Android |
| [`kormium-r2dbc`](../kormium-r2dbc/README.md) | You want non-blocking PostgreSQL on JVM |
| [`kormium-jdbc`](../kormium-jdbc/README.md) | You implement a custom JDBC backend (shared JVM plumbing) |
| [`kormium-migrate`](../kormium-migrate/README.md) | You want a raw-SQL schema migration runner |
| [`kormium-observe`](../kormium-observe/README.md) | You want reactive `Flow` queries that re-emit when data changes |
| [`kormium-ktor`](../kormium-ktor/README.md) | You want explicit database passing in Ktor routes |
| [`kormium-ktor-di`](../kormium-ktor-di/README.md) | You use Ktor's built-in DI container |
| [`kormium-ktor-koin`](../kormium-ktor-koin/README.md) | You use Koin in a Ktor application |
| [`kormium-bom`](../kormium-bom/README.md) | Always — pins one consistent version across all artifacts |

Backend artifacts bring `kormium-core` transitively. `kormium-observe` is pure common code and
supports the same targets as `kormium-core` (JVM, Native, Android, iOS).

## Native Libraries

### PostgreSQL Native

`kormium-postgres` links against `libpq` on Kotlin/Native.

```bash
# macOS
brew install libpq

# Debian / Ubuntu
sudo apt-get install libpq-dev
```

Windows Native (mingwX64) is experimental: CI runs the JVM and native test suites on a
Windows runner. Install a Windows libpq via MSYS2
(`pacman -S mingw-w64-x86_64-postgresql`) or an EDB PostgreSQL distribution; the cinterop
searches `C:/msys64/mingw64` and `C:\Program Files\PostgreSQL\15..17`. For other
layouts pass `-Plibpq.include=<dir>` and `-Plibpq.lib=<dir>` to Gradle.

### SQLite Native

`kormium-sqlite` links against `sqlite3` on Kotlin/Native. macOS and iOS ship SQLite. On
Debian/Ubuntu install headers if missing:

```bash
sudo apt-get install libsqlite3-dev
```

## Platform Matrix

| Platform | `kormium-core` | `kormium-postgres` | `kormium-sqlite` | `kormium-r2dbc` | Ktor modules |
| --- | --- | --- | --- | --- | --- |
| JVM | Yes | JDBC/HikariCP | sqlite-jdbc | Yes | Yes |
| Linux Native | Yes | libpq | sqlite3 | No | Yes |
| macOS Native | Yes | libpq | sqlite3 | No | Yes |
| Android | Yes | No | AndroidX SQLite | No | Compiles |
| iOS | Yes | No | sqlite3 | No | Compiles |
| Windows Native | Experimental | libpq (experimental) | sqlite3 (experimental) | No | Experimental |
| Wasm | Research | No | Planned | No | No |

The CI workflow builds JVM/Linux Native tests on Ubuntu and Android/iOS compilation on
macOS. Local `./gradlew check` may try host-specific native simulator tests; use the focused
commands in [Project guide](project.md#testing) when your local SDK does not provide every
target.
