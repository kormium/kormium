package io.github.kormium

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Opens a SQLite database and returns a [SqliteDriver].
 *
 * @param path the database file path, or `":memory:"` (the default) for an in-memory
 *   database. On JVM/native each `":memory:"` call gets its own private database — two
 *   `createSqliteDatabase()` calls never see each other's data — shared across that one
 *   driver's [poolSize] connections; it lives only while the driver is open. On Android
 *   (androidx.sqlite) an in-memory database is private per connection, so there [poolSize]
 *   must be 1 — a larger pool is rejected; use a file path for a shared pool. A file-backed
 *   database is opened in WAL (write-ahead logging) mode for better read/write concurrency.
 *   To share one in-memory database between drivers on JVM/native, pass an explicit SQLite
 *   URI instead: `createSqliteDatabase("file:shared?mode=memory&cache=shared")`.
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

/**
 * A process-unique name for an in-memory database, used by the JVM and native drivers to build
 * a `file:<name>?mode=memory&cache=shared` URI.
 *
 * Shared cache is what lets a driver's `poolSize` connections see one database, but the plain
 * `file::memory:?cache=shared` URI is identical for every caller, so unrelated
 * `createSqliteDatabase()` calls in one process silently landed on the same physical database
 * (https://github.com/kormium/kormium/issues/131). Naming each one keeps the pool sharing while
 * isolating drivers from each other. A counter is enough: shared-cache in-memory databases are
 * per-process, so uniqueness within this process is uniqueness everywhere it matters.
 */
@OptIn(ExperimentalAtomicApi::class)
internal fun newInMemoryDatabaseName(): String = "kormium-mem-${inMemoryDatabaseCounter.fetchAndAdd(1)}"

@OptIn(ExperimentalAtomicApi::class)
private val inMemoryDatabaseCounter = AtomicLong(0)
