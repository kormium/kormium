package io.github.kormium

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Opens a SQLite database and returns a [SqliteDriver].
 *
 * @param path the database file path, or `":memory:"` (the default) for an in-memory
 *   database. On JVM/native, with the default `poolSize = 1` an in-memory database is private
 *   to that one connection, so separate `createSqliteDatabase()` calls never see each other's
 *   data; with `poolSize > 1` it is opened in shared-cache mode instead, so the pool's
 *   connections all see the same database — it lives only while the driver is open. On
 *   Android (androidx.sqlite) an in-memory database is private per connection, so there
 *   [poolSize] must be 1 — a larger pool is rejected; use a file path for a shared pool.
 *   A file-backed database is opened in WAL (write-ahead logging) mode for better
 *   read/write concurrency.
 * @param poolSize how many connections to keep. SQLite allows a single writer, so the
 *   default is 1 (everything serialised, no `database is locked`); raise it for
 *   concurrent reads (WAL permits many readers alongside one writer).
 * @param acquireTimeout how long a caller may wait for a pooled connection when all
 *   [poolSize] connections are busy before failing with [PoolExhaustedException] (on the
 *   JVM this is HikariCP's `connectionTimeout`, which has a 250 ms floor). A bounded wait
 *   turns a saturated pool — e.g. `poolSize = 1` and a long transaction — into a clear,
 *   catchable error instead of an indefinite hang.
 */
public expect fun createSqliteDatabase(
    path: String = ":memory:",
    poolSize: Int = 1,
    acquireTimeout: Duration = 30.seconds,
    config: KormiumConfig = KormiumConfig(),
): SqliteDriver

/**
 * Opens a SQLite database with a configuration block: `createSqliteDatabase("app.db") {`
 * `config { … }; beforeStart { migrate(appMigrations) } }`. See [KormiumBuilder].
 */
public fun createSqliteDatabase(
    path: String = ":memory:",
    poolSize: Int = 1,
    acquireTimeout: Duration = 30.seconds,
    block: KormiumBuilder.() -> Unit,
): SqliteDriver = KormiumBuilder().apply(block).finish { createSqliteDatabase(path, poolSize, acquireTimeout, it) }
