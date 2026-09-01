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
 *
 *   A `"file:…"` path is handed to SQLite as a URI (JVM and native only — Android rejects it,
 *   androidx.sqlite opens without `SQLITE_OPEN_URI`). That is how to share one in-memory
 *   database between drivers: `createSqliteDatabase("file:shared?mode=memory&cache=shared")`.
 *   Kormium adds `journal_mode`, `foreign_keys` and `busy_timeout` to a URI only when the
 *   caller has not spelled them out, so its defaults never override an explicit choice.
 * @param poolSize how many connections to keep. SQLite allows a single writer, so the
 *   default is 1 (everything serialised, no `database is locked`); raise it for
 *   concurrent reads (WAL permits many readers alongside one writer).
 * @param acquireTimeout how long a caller may wait for a pooled connection when all
 *   [poolSize] connections are busy before failing with [PoolExhaustedException] (on the
 *   JVM this is HikariCP's `connectionTimeout`, which has a 250 ms floor). A bounded wait
 *   turns a saturated pool — e.g. `poolSize = 1` and a long transaction — into a clear,
 *   catchable error instead of an indefinite hang.
 * @param options SQLite-specific options — extensions to install and pragmas to apply on every
 *   connection this driver opens. Usually built with the `sqlite { }` block of the builder
 *   overload below. An extension that cannot be installed fails this call, so the problem
 *   surfaces at startup rather than at the first query that needed it.
 */
public expect fun createSqliteDatabase(
    path: String = ":memory:",
    poolSize: Int = 1,
    acquireTimeout: Duration = 30.seconds,
    config: KormiumConfig = KormiumConfig(),
    options: SqliteOptions = SqliteOptions(),
): SqliteDriver

/**
 * Opens a SQLite database with a configuration block: `createSqliteDatabase("app.db") {`
 * `config { … }; sqlite { extension(SqliteVec) }; beforeStart { migrate(appMigrations) } }`.
 * See [SqliteBuilder].
 */
public fun createSqliteDatabase(
    path: String = ":memory:",
    poolSize: Int = 1,
    acquireTimeout: Duration = 30.seconds,
    block: SqliteBuilder.() -> Unit,
): SqliteDriver {
    val builder = SqliteBuilder().apply(block)
    return builder.finish { createSqliteDatabase(path, poolSize, acquireTimeout, it, builder.options()) }
}

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

/**
 * Whether [path] names an in-memory database rather than a file — `":memory:"`, the
 * `file:<name>?mode=memory` URI built above, or a caller's own `file::memory:` spelling.
 *
 * Two decisions hang on it: WAL is meaningless without a file, and an in-memory database exists
 * only while some connection to it is open, so the JVM driver has to hold one open itself.
 */
internal fun isInMemorySqlitePath(path: String): Boolean =
    ":memory:" in path || sqlitePathParams(path)["mode"] == "memory"

/**
 * The query parameters of a SQLite path — `file:app.db?busy_timeout=60000` yields
 * `{busy_timeout=60000}`; a path without a `?` yields an empty map.
 *
 * Kormium applies three pragmas of its own (`journal_mode`, `foreign_keys`, `busy_timeout`).
 * A caller who writes one of them into the path means it, so both drivers consult this map
 * first and fall back to the default only for what is missing — on the JVM by not appending
 * the parameter (sqlite-jdbc applies the caller's), on native by issuing the caller's value as
 * the `PRAGMA`. Values are restricted to word characters: they are interpolated into a `PRAGMA`
 * statement on native, and nothing legitimate here is more than a keyword or a number.
 */
internal fun sqlitePathParams(path: String): Map<String, String> {
    val query = path.substringAfter('?', "")
    if (query.isEmpty()) return emptyMap()
    return query.split('&').mapNotNull { part ->
        val key = part.substringBefore('=', "")
        val value = part.substringAfter('=', "")
        if (key.isEmpty() || value.isEmpty() || !value.all { it == '_' || it.isLetterOrDigit() }) null else key to value
    }.toMap()
}
