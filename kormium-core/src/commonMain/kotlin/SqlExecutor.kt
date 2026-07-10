package io.github.kormium

import io.github.kormium.resultset.ResultSet

/**
 * Runs SQL against one connection, carrying the [dialect] and [typeMapper] used to
 * render statements and convert values. A [io.github.kormium.database.Database]
 * is an [SqlExecutor] backed by a pool; inside a transaction / autocommit scope the
 * executor is pinned to a single connection.
 */
public interface SqlExecutor {
    public val dialect: Dialect
    public val typeMapper: TypeMapper

    public fun <T> execute(sql: String, namedParameters: Map<String, Any?> = emptyMap(), handler: (ResultSet) -> T): List<T>
    public fun <T> execute(sql: String, paramSource: SqlParameterSource, handler: (ResultSet) -> T): List<T>
    public fun execute(sql: String, namedParameters: Map<String, Any?> = emptyMap()): Long
    public fun execute(sql: String, paramSource: SqlParameterSource): Long
    /** Runs an INSERT/UPDATE/DELETE/DDL statement and returns the backend-reported affected row count. */
    public fun executeUpdate(sql: String, namedParameters: Map<String, Any?> = emptyMap()): Long
}
