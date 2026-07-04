package io.github.kormium

import io.github.kormium.resultset.ResultSet

/**
 * The suspend mirror of [SqlExecutor]: runs SQL against one connection inside a
 * [io.github.kormium.database.SuspendDatabase.useConnection] block. The
 * [dialect] and [typeMapper] are the same seams used to render statements and
 * convert values.
 *
 * Backends provide their own implementation: the offload backends wrap a blocking
 * [SqlExecutor] and `withContext(ioDispatcher)` each call; a truly async backend
 * (e.g. r2dbc) implements it natively. It is a SIBLING of [SqlExecutor], not a
 * subtype — one object can't be both (a `suspend` and a non-`suspend` `execute`
 * with the same parameters clash).
 */
public interface SuspendSqlExecutor {
    public val dialect: Dialect
    public val typeMapper: TypeMapper

    public suspend fun <T> execute(sql: String, namedParameters: Map<String, Any?> = emptyMap(), handler: (ResultSet) -> T): List<T>
    public suspend fun <T> execute(sql: String, paramSource: SqlParameterSource, handler: (ResultSet) -> T): List<T>
    public suspend fun execute(sql: String, namedParameters: Map<String, Any?> = emptyMap()): Long
    public suspend fun execute(sql: String, paramSource: SqlParameterSource): Long
    /** Runs an INSERT/UPDATE/DELETE/DDL statement and returns the backend-reported affected row count. */
    public suspend fun executeUpdate(sql: String, namedParameters: Map<String, Any?> = emptyMap()): Long
}
