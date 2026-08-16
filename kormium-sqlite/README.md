# korm-sqlite

The SQLite backend for [Kormium](../readme.md). Provides `createSqliteDatabase(...)`, the
`SqliteDialect` and the `SqliteDriver`. Good for desktop/mobile apps, tests, and as a local
cache in front of a server database.

Three transports, picked per platform:

- **JVM** — sqlite-jdbc.
- **Native** (Linux/macOS/iOS) — the vendored SQLite amalgamation compiled with the
  Kotlin/Native toolchain and embedded as a static library (self-contained, FTS5 + RTREE
  enabled).
- **Android** — androidx.sqlite with the bundled SQLite native library.

## Install

```kotlin
dependencies {
    implementation(platform("io.github.kormium:korm-bom:<version>"))
    implementation("io.github.kormium:korm-sqlite")
}
```

`korm-core` is pulled in transitively.

On Kotlin/Native for Linux you may need SQLite headers (`sudo apt-get install libsqlite3-dev`);
macOS, iOS and Android need nothing extra.

## Example

```kotlin
// In-memory: private to this driver (its pool shares it), lives only while the driver is open.
val db: Database<App> = createSqliteDatabase()

// File-backed, opened in WAL mode. SQLite has a single writer, so poolSize defaults to 1;
// raise it for concurrent readers (WAL allows many readers alongside one writer).
val app: Database<App> = createSqliteDatabase("app.db", poolSize = 4)

val users = app.autocommit { Users.all() }
```

With a configuration block:

```kotlin
val db = createSqliteDatabase("app.db") {
    config { /* KormConfig tuning */ }
    beforeStart { migrate(appMigrations) } // needs korm-migrate
}
```

> On Android an in-memory database is private per connection, so `poolSize` must be `1`; use a
> file path for a shared pool.

## Platforms

JVM, Linux/macOS Native, iOS (sqlite3 cinterop) and Android (AndroidX SQLite).

## Documentation

- [Backends and platform support](../docs/backends.md)
- [Installation](../docs/installation.md)
- See also [`samples/crud-sqlite`](../samples/crud-sqlite) and
  [`samples/sqlite-cache`](../samples/sqlite-cache).
