package io.github.kormium

import io.github.kormium.jdbc.JdbcDatabase
import io.github.kormium.jdbc.SqlExceptionTranslator
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
private fun sqliteJdbcUrl(path: String): String = when {
    // Shared cache so this driver's pool all sees one database, under a process-unique name so
    // unrelated createSqliteDatabase() calls do not (issue #131). WAL is meaningless without a
    // file, so it is omitted here.
    path == ":memory:" ->
        "jdbc:sqlite:file:${newInMemoryDatabaseName()}?mode=memory&cache=shared&foreign_keys=on&busy_timeout=5000"

    // A caller-supplied SQLite URI — the way to opt back into one in-memory database shared by
    // several drivers ("file:shared?mode=memory&cache=shared"). It carries its own parameters,
    // so ours are appended instead of replacing the `?`; the pragmas are the same as for a
    // plain path (journal_mode=WAL is simply a no-op on an in-memory database).
    path.startsWith("file:") ->
        "jdbc:sqlite:$path${if ('?' in path) "&" else "?"}journal_mode=WAL&foreign_keys=on&busy_timeout=5000"

    // WAL gives concurrent readers alongside one writer; foreign_keys are OFF by
    // default in SQLite, so enable them to surface ForeignKeyViolationException.
    else -> "jdbc:sqlite:$path?journal_mode=WAL&foreign_keys=on&busy_timeout=5000"
}

private class SqliteJdbcDriver(path: String, poolSize: Int, acquireTimeout: Duration, config: KormiumConfig) :
    JdbcDatabase(
        jdbcUrl = sqliteJdbcUrl(path),
        poolSize = poolSize,
        acquireTimeout = acquireTimeout,
        dialect = SqliteDialect,
        typeMapper = StandardTypeMapper,
        wrap = ::SqliteResultSetWrapper,
        translate = sqliteTranslator,
        config = config,
    ),
    SqliteDriver

public actual fun createSqliteDatabase(
    path: String,
    poolSize: Int,
    acquireTimeout: Duration,
    config: KormiumConfig,
): SqliteDriver = SqliteJdbcDriver(path, poolSize, acquireTimeout, config)
