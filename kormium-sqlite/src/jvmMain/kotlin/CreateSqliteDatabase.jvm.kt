package io.github.kormium

import io.github.kormium.jdbc.JdbcDatabase
import io.github.kormium.jdbc.SqlExceptionTranslator
import java.sql.SQLException
import kotlin.time.Duration

// sqlite-jdbc exposes the SQLite (extended) result code via SQLException.getErrorCode().
private val sqliteTranslator: SqlExceptionTranslator = { e: SQLException ->
    sqliteException(e.message ?: "SQL error", e.errorCode.takeIf { it != 0 }, e)
}

private fun sqliteJdbcUrl(path: String): String =
    if (path == ":memory:") {
        // Shared cache so a pool of connections all see the same in-memory database.
        // WAL is meaningless without a file, so it is omitted here.
        "jdbc:sqlite:file::memory:?cache=shared&foreign_keys=on&busy_timeout=5000"
    } else {
        // WAL gives concurrent readers alongside one writer; foreign_keys are OFF by
        // default in SQLite, so enable them to surface ForeignKeyViolationException.
        "jdbc:sqlite:$path?journal_mode=WAL&foreign_keys=on&busy_timeout=5000"
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
