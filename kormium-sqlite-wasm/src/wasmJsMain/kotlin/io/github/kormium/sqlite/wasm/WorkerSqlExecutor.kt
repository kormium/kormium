package io.github.kormium.sqlite.wasm

import io.github.kormium.Dialect
import io.github.kormium.SqlParameterSource
import io.github.kormium.SuspendSqlExecutor
import io.github.kormium.TypeMapper
import io.github.kormium.resultset.ResultSet
import io.github.kormium.wasm.driver.QuestionMarker
import io.github.kormium.wasm.driver.parseNamedParams

/**
 * A [SuspendSqlExecutor] bound to one [WorkerConnection] (one Worker in the pool). SQL
 * rendering/value conversion are the same shared core seams as every other engine; `:name` → `?`
 * rewriting reuses [parseNamedParams] from `kormium-wasm-driver`.
 */
internal class WorkerSqlExecutor(
    private val connection: WorkerConnection,
    override val dialect: Dialect,
    override val typeMapper: TypeMapper,
) : SuspendSqlExecutor {

    private fun encodeParams(namedParameters: Map<String, Any?>, names: List<String>): List<Any?> =
        names.map { name ->
            require(namedParameters.containsKey(name)) { "No value supplied for parameter \"$name\"" }
            typeMapper.toParameter(namedParameters[name])
        }

    override suspend fun <T> execute(sql: String, namedParameters: Map<String, Any?>, handler: (ResultSet) -> T): List<T> {
        val parsed = parseNamedParams(sql, QuestionMarker)
        val result = connection.query(parsed.sql, encodeParams(namedParameters, parsed.names))
        val columns = result.columnNames.toTypedArray()
        return result.rows.map { row -> handler(WorkerRowResultSet(row, columns)) }
    }

    override suspend fun <T> execute(sql: String, paramSource: SqlParameterSource, handler: (ResultSet) -> T): List<T> =
        execute(sql, paramSource.toMap(), handler)

    override suspend fun execute(sql: String, namedParameters: Map<String, Any?>): Long {
        val parsed = parseNamedParams(sql, QuestionMarker)
        return connection.execute(parsed.sql, encodeParams(namedParameters, parsed.names))
    }

    override suspend fun execute(sql: String, paramSource: SqlParameterSource): Long = execute(sql, paramSource.toMap())

    override suspend fun executeUpdate(sql: String, namedParameters: Map<String, Any?>): Long = execute(sql, namedParameters)
}

private fun SqlParameterSource.toMap(): Map<String, Any?> =
    (parameterNames ?: emptyArray()).associateWith { getValue(it) }
