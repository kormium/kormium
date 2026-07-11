package io.github.kormium.database

import io.github.kormium.KormiumConfig
import io.github.kormium.PgResultSetWrapper
import io.github.kormium.PostgresDialect
import io.github.kormium.PostgresDriver
import io.github.kormium.PostgresJvmTypeMapper
import io.github.kormium.jdbc.JdbcDatabase
import kotlin.time.Duration

/**
 * A Postgres [PostgresDriver] backed by the shared [JdbcDatabase] (HikariCP pool).
 *
 * Parameters are bound as properly-typed JDBC objects via [PostgresJvmTypeMapper], so a
 * server-prepared statement declares its real parameter types and each execution is one
 * protocol round-trip. (The previous `stringtype=unspecified` text binding made the server
 * re-infer untyped parameters — an extra round-trip on every execution, ~2x on reads.)
 * Raw SQL that binds a *String* to a non-text column (uuid, timestamptz, numeric, ...)
 * must now cast explicitly, e.g. `WHERE id = :id::uuid`; DSL queries need nothing.
 * prepareThreshold=1 makes pgjdbc use a server-side prepared statement from the first
 * execution (default 5), so repeated statements skip re-parsing. (The MySQL-style
 * cachePrepStmts/prepStmtCacheSize properties are ignored by pgjdbc.)
 */
private class PostgresJdbcDriver(
    host: String,
    port: Int,
    database: String,
    user: String,
    password: String,
    poolSize: Int,
    acquireTimeout: Duration,
    config: KormiumConfig,
) : JdbcDatabase(
    jdbcUrl = "jdbc:postgresql://$host:$port/$database?prepareThreshold=1",
    username = user,
    password = password,
    poolSize = poolSize,
    acquireTimeout = acquireTimeout,
    dialect = PostgresDialect,
    typeMapper = PostgresJvmTypeMapper,
    wrap = ::PgResultSetWrapper,
    config = config,
), PostgresDriver

public actual fun createDatabase(
    host: String,
    port: Int,
    database: String,
    user: String,
    password: String,
    poolSize: Int,
    acquireTimeout: Duration,
    config: KormiumConfig,
): PostgresDriver = PostgresJdbcDriver(host, port, database, user, password, poolSize, acquireTimeout, config)
