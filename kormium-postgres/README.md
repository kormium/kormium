# kormium-postgres

The PostgreSQL backend for [Kormium](../readme.md). Provides `createDatabase(...)`, the
`PostgresDialect` and the `PostgresDriver`, with two interchangeable transports:

- **JVM** — JDBC via pgjdbc, pooled with HikariCP.
- **Native** (Linux/macOS) — libpq through cinterop, no JVM required.

For non-blocking PostgreSQL on the JVM, see [`kormium-r2dbc`](../kormium-r2dbc/README.md).

## Install

```kotlin
dependencies {
    implementation(platform("io.github.kormium:kormium-bom:<version>"))
    implementation("io.github.kormium:kormium-postgres")
}
```

`kormium-core` is pulled in transitively.

### Native system library

On Kotlin/Native the driver links against `libpq`:

```bash
# macOS
brew install libpq
# Debian / Ubuntu
sudo apt-get install libpq-dev
```

## Example

```kotlin
val db: Database<App> = createDatabase(
    host = "localhost",
    port = 5432,
    database = "postgres",
    user = "postgres",
    password = "password",
    poolSize = 10,
)

val adults = db.autocommit {
    Users.find { where { Users.age gtEq 18 } }
}
```

With a configuration block (run migrations before the first connection is handed out):

```kotlin
val db = createDatabase(host = "localhost", database = "app", user = "postgres", password = "secret") {
    config { /* KormiumConfig tuning */ }
    beforeStart { migrate(appMigrations) } // needs kormium-migrate
}
```

## Platforms

| Platform | Transport |
| --- | --- |
| JVM | JDBC / HikariCP |
| Linux Native | libpq |
| macOS Native | libpq |
| Windows Native | Planned |

Android and iOS do not ship a PostgreSQL backend — use `kormium-sqlite` there.

## Documentation

- [Backends and platform support](../docs/backends.md)
- [Installation](../docs/installation.md)
- [Quick start](../docs/quick-start.md)
