package io.github.kormium

/**
 * Opens a SQLite database and returns a [SqliteDriver].
 *
 * @param path the database file path, or `":memory:"` (the default) for an in-memory
 *   database. On JVM/native an in-memory database is opened in shared-cache mode so a pool
 *   of connections all see the same database; it lives only while the driver is open. On
 *   Android (androidx.sqlite) an in-memory database is private per connection, so there
 *   [poolSize] must be 1 — a larger pool is rejected; use a file path for a shared pool.
 *   A file-backed database is opened in WAL (write-ahead logging) mode for better
 *   read/write concurrency.
 * @param poolSize how many connections to keep. SQLite allows a single writer, so the
 *   default is 1 (everything serialised, no `database is locked`); raise it for
 *   concurrent reads (WAL permits many readers alongside one writer).
 */
public expect fun createSqliteDatabase(
    path: String = ":memory:",
    poolSize: Int = 1,
    config: KormiumConfig = KormiumConfig(),
): SqliteDriver

/**
 * Opens a SQLite database with a configuration block: `createSqliteDatabase("app.db") {`
 * `config { … }; beforeStart { migrate(appMigrations) } }`. See [KormiumBuilder].
 */
public fun createSqliteDatabase(
    path: String = ":memory:",
    poolSize: Int = 1,
    block: KormiumBuilder.() -> Unit,
): SqliteDriver = KormiumBuilder().apply(block).finish { createSqliteDatabase(path, poolSize, it) }
