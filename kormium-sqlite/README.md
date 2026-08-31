# kormium-sqlite

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
    implementation(platform("io.github.kormium:kormium-bom:<version>"))
    implementation("io.github.kormium:kormium-sqlite")
}
```

`kormium-core` is pulled in transitively.

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
    config { /* KormiumConfig tuning */ }
    sqlite { pragma("cache_size", "-64000") }
    beforeStart { migrate(appMigrations) } // needs kormium-migrate
}
```

## Extensions and pragmas

The `sqlite { }` block configures the connections themselves. Everything declared there is applied
to **every** connection the driver opens, including ones the pool recreates later — which is why it
is not a `beforeStart` hook.

```kotlin
val db = createSqliteDatabase("app.db", poolSize = 4) {
    sqlite {
        extension(SqliteVec)                // from an extension package
        pragma("cache_size", "-64000")
        pragma("mmap_size", "268435456")
    }
}
```

A pragma set here wins over Kormium's own default for it (`journal_mode`, `foreign_keys`,
`busy_timeout`), exactly as one written into a `file:` path does.

Extensions are ordinary dependencies implementing `SqliteExtension` (from `kormium-sqlite-spi`);
Kormium ships none and curates no list. An extension that cannot be installed fails the
`createSqliteDatabase` call, so the problem shows up at startup rather than at the first query that
needed it. See `samples/sqlite-vec` for a working reference package and
[ADR 0013](../docs/adr/0013-sqlite-extensions.md) for the design.

Support differs by engine: JVM and Node load a shared library per connection; Kotlin/Native and
iOS link the extension statically and register it before the pool opens. Android and the browser
engines do not support extensions yet and say so — `loadLibrary` throws
`SqliteExtensionUnsupportedException`.

> On Kotlin/Native, static registration is process-global: once any database declares an extension,
> every SQLite connection opened afterwards in the process has it, including databases that never
> declared it.

Two `createSqliteDatabase()` calls never share an in-memory database. To put several drivers on
one, name it with a SQLite URI (JVM and native only):

```kotlin
val a = createSqliteDatabase("file:shared?mode=memory&cache=shared")
val b = createSqliteDatabase("file:shared?mode=memory&cache=shared") // same database as `a`
```

`journal_mode`, `foreign_keys` and `busy_timeout` written into such a path are honoured as-is;
Kormium only fills in the ones you left out.

> On Android an in-memory database is private per connection, so `poolSize` must be `1`; use a
> file path for a shared pool. Android also rejects `file:` URIs — androidx.sqlite opens without
> `SQLITE_OPEN_URI`, so the URI would become a file of that name.

## Platforms

JVM, Linux/macOS Native, iOS (sqlite3 cinterop) and Android (AndroidX SQLite).

## Documentation

- [Backends and platform support](../docs/backends.md)
- [Installation](../docs/installation.md)
- See also [`samples/crud-sqlite`](../samples/crud-sqlite) and
  [`samples/sqlite-cache`](../samples/sqlite-cache).
