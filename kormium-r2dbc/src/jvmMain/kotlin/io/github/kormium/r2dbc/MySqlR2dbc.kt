package io.github.kormium.r2dbc

import io.github.kormium.KormiumConfig
import io.github.kormium.MySqlDialect
import io.github.kormium.MySqlJvmTypeMapper
import io.github.kormium.mysqlVendorException
import io.asyncer.r2dbc.mysql.MySqlConnectionConfiguration
import io.asyncer.r2dbc.mysql.MySqlConnectionFactory
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration

/**
 * Opens an async MySQL/MariaDB database over r2dbc-mysql with a reactive connection pool of
 * [poolSize]. Mirrors [createR2dbcDatabase] (Postgres) but wires the MySQL pieces into the generic
 * [R2dbcDatabase]:
 *
 *  - [MySqlDialect] for SQL rendering (backtick identifiers, MySQL LIMIT/OFFSET);
 *  - [QuestionMarkParamMarker] so `:name` placeholders are rewritten to `?` (r2dbc-mysql's marker);
 *  - [MySqlJvmTypeMapper] so UUID/JSON bind as text and date/times bind as native `java.time`
 *    values (an [kotlin.time.Instant] as a UTC `OffsetDateTime`), with the session pinned to
 *    UTC so timestamps round-trip unchanged.
 *
 * Returns it tagged [Nothing] (covariance pins the catalog at the call site).
 */
public fun createMySqlR2dbcDatabase(
    host: String,
    port: Int = 3306,
    database: String,
    user: String,
    password: String,
    poolSize: Int = 10,
    config: KormiumConfig = KormiumConfig(),
): R2dbcDatabase {
    val connectionFactory = MySqlConnectionFactory.from(
        MySqlConnectionConfiguration.builder()
            .host(host)
            .port(port)
            .database(database)
            .user(user)
            .password(password)
            .connectionTimeZone("UTC")
            .forceConnectionTimeZoneToSession(true)
            .build(),
    )
    val poolConfiguration = ConnectionPoolConfiguration.builder(connectionFactory)
        .maxSize(poolSize)
        .build()
    return R2dbcDatabase(
        ConnectionPool(poolConfiguration),
        MySqlDialect,
        MySqlJvmTypeMapper,
        QuestionMarkParamMarker,
        // MySQL reports integrity violations under SQLSTATE 23000; map by vendor code instead.
        { e -> mysqlVendorException(e.message ?: "SQL error", e.errorCode, e.sqlState, e) },
        config,
    )
}
