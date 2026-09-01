package io.github.kormium

import io.github.kormium.jdbc.JdbcDatabase
import io.github.kormium.jdbc.SqlExceptionTranslator
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.time.Duration

// sqlite-jdbc exposes the SQLite (extended) result code via SQLException.getErrorCode().
private val sqliteTranslator: SqlExceptionTranslator = { e: SQLException ->
    sqliteException(e.message ?: "SQL error", e.errorCode.takeIf { it != 0 }, e)
}

// sqlite-jdbc splits everything after the first `?` into pragmas it applies itself
// (foreign_keys, busy_timeout, journal_mode) and unknown parameters, which it leaves on the
// filename — so `mode`/`cache` still reach SQLite as URI parameters, and a `file:` filename
// makes it open with SQLITE_OPEN_URI.
//
// `:memory:` becomes a process-unique shared-cache URI: shared cache is what lets this driver's
// pool see one database, but the plain `file::memory:?cache=shared` URI is identical for every
// caller, so unrelated createSqliteDatabase() calls used to land on the same physical database
// (issue #131). A caller's own `file:` URI is otherwise left as written — that is the way to opt
// back into one in-memory database behind several drivers — and only the pragmas it does not
// already set are appended.
private fun sqliteJdbcUrl(path: String, declaredPragmas: Set<String> = emptySet()): String {
    val filename = if (path == ":memory:") "file:${newInMemoryDatabaseName()}?mode=memory&cache=shared" else path
    val callerParams = sqlitePathParams(filename)
    // WAL gives concurrent readers alongside one writer (and is meaningless without a file);
    // foreign keys are OFF by default in SQLite, so enable them to surface
    // ForeignKeyViolationException. Anything the caller spelled out in the path wins.
    val defaults = buildList {
        if (!isInMemorySqlitePath(filename)) add("journal_mode" to "WAL")
        add("foreign_keys" to "on")
        add("busy_timeout" to "5000")
    }.filterNot { (key, _) -> key in callerParams || key in declaredPragmas }
    val appended = if (defaults.isEmpty()) {
        ""
    } else {
        defaults.joinToString("&", prefix = if ('?' in filename) "&" else "?") { (key, value) -> "$key=$value" }
    }
    return "jdbc:sqlite:$filename$appended"
}

private class SqliteJdbcDriver(
    jdbcUrl: String,
    poolSize: Int,
    acquireTimeout: Duration,
    config: KormiumConfig,
    options: SqliteOptions,
) : JdbcDatabase(
    jdbcUrl = jdbcUrl,
    poolSize = poolSize,
    acquireTimeout = acquireTimeout,
    dialect = SqliteDialect,
    typeMapper = StandardTypeMapper,
    wrap = ::SqliteResultSetWrapper,
    translate = sqliteTranslator,
    // Extensions and pragmas are per-connection, and Hikari recreates connections on maxLifetime,
    // so they have to be reapplied on each one — hence the hook rather than a startup block. Left
    // null when nothing was declared, keeping the plain jdbcUrl path for everyone else.
    onConnection = if (options.isEmpty) null else { conn -> options.applyTo(SqliteJdbcConnectionScope(conn)) },
    config = config,
),
    SqliteDriver {

    // An in-memory database exists only while at least one connection to it is open, and HikariCP
    // retires pooled connections behind our back (maxLifetime, 30 minutes by default; also on any
    // fatal error). At the default poolSize = 1 that leaves an instant with no connection at all,
    // which drops the database — the next caller silently gets an empty one. This connection is
    // never pooled and never handed out; it exists to outlive that churn.
    private val keepAlive: Connection? = openKeepAlive(jdbcUrl)

    private fun openKeepAlive(jdbcUrl: String): Connection? {
        if (!isInMemorySqlitePath(jdbcUrl)) return null
        return try {
            DriverManager.getConnection(jdbcUrl)
        } catch (e: SQLException) {
            super.close() // the pool is already up at this point; don't leak it
            throw sqliteException(e.message ?: "SQL error", e.errorCode.takeIf { it != 0 }, e)
        }
    }

    // The pool closes first: while it drains, the database must still exist. Closing the
    // keep-alive last is what finally frees an in-memory database.
    override fun close() {
        try {
            super.close()
        } finally {
            keepAlive?.close()
        }
    }
}

public actual fun createSqliteDatabase(
    path: String,
    poolSize: Int,
    acquireTimeout: Duration,
    config: KormiumConfig,
    options: SqliteOptions,
): SqliteDriver {
    // Process-global registration (sqlite3_auto_extension on the engines that use it) has to
    // happen before the pool opens its first connection.
    options.beforeOpen(perConnectionRegistration(SqliteEngine.Xerial))
    return SqliteJdbcDriver(sqliteJdbcUrl(path, options.declaredPragmas()), poolSize, acquireTimeout, config, options)
}
