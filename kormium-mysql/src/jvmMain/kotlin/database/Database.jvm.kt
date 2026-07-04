package io.github.kormium.database

import io.github.kormium.KormiumConfig
import io.github.kormium.MySqlDialect
import io.github.kormium.MySqlDriver
import io.github.kormium.MySqlExceptionTranslator
import io.github.kormium.MySqlJvmTypeMapper
import io.github.kormium.MySqlResultSetWrapper
import io.github.kormium.jdbc.JdbcDatabase

/**
 * A MySQL/MariaDB [MySqlDriver] backed by the shared [JdbcDatabase] (HikariCP pool over
 * mysql-connector-j). Mirrors the Postgres JDBC driver.
 *
 * URL parameters:
 *  - `cachePrepStmts` + `useServerPrepStmts` make repeated statements server-prepared and cached,
 *    so each execution is one round-trip (the MySQL analogue of pgjdbc's prepareThreshold=1).
 *  - `connectionTimeZone=UTC` + `forceConnectionTimeZoneToSession=true` pin the session to UTC so
 *    an [kotlin.time.Instant] bound as a UTC `OffsetDateTime` round-trips through a
 *    `TIMESTAMP` unchanged, and is read back correctly by [MySqlResultSetWrapper.getInstant].
 *
 * Integrity violations are mapped by vendor code through [MySqlExceptionTranslator] (MySQL reports
 * them all under SQLSTATE 23000, which the standard SQLSTATE translator can't disambiguate).
 */
private class MySqlJdbcDriver(
    host: String,
    port: Int,
    database: String,
    user: String,
    password: String,
    poolSize: Int,
    config: KormiumConfig,
) : JdbcDatabase(
    jdbcUrl = "jdbc:mysql://$host:$port/$database" +
        "?cachePrepStmts=true&useServerPrepStmts=true" +
        "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
    username = user,
    password = password,
    poolSize = poolSize,
    dialect = MySqlDialect,
    typeMapper = MySqlJvmTypeMapper,
    wrap = ::MySqlResultSetWrapper,
    translate = MySqlExceptionTranslator,
    config = config,
), MySqlDriver

actual fun createDatabase(
    host: String,
    port: Int,
    database: String,
    user: String,
    password: String,
    poolSize: Int,
    config: KormiumConfig,
): MySqlDriver = MySqlJdbcDriver(host, port, database, user, password, poolSize, config)
