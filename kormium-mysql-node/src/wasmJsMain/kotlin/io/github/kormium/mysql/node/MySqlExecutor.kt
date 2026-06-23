package io.github.kormium.mysql.node

import io.github.kormium.Dialect
import io.github.kormium.SqlParameterSource
import io.github.kormium.SuspendSqlExecutor
import io.github.kormium.TypeMapper
import io.github.kormium.resultset.ResultSet
import io.github.kormium.sqlException
import kotlinx.coroutines.await

/**
 * A [SuspendSqlExecutor] bound to one mysql2 [MySqlConnection]. Drives the async driver and bridges
 * its `Promise` to suspend via `await()`. SQL rendering ([Dialect]) and value conversion
 * ([TypeMapper]) are the shared core seams; `:name` is rewritten to `?` and values bound as text.
 */
internal class MySqlExecutor(
    private val connection: MySqlConnection,
    override val dialect: Dialect,
    override val typeMapper: TypeMapper,
) : SuspendSqlExecutor {

    private suspend fun run(sql: String, namedParameters: Map<String, Any?>): JsArray<JsAny?> {
        val parsed = parseNamedParams(sql)
        val params = newJsArray()
        for (name in parsed.names) {
            require(namedParameters.containsKey(name)) { "No value supplied for parameter \"$name\"" }
            val mapped = typeMapper.toParameter(namedParameters[name])
            pushParam(params, mapped?.toString()?.toJsString())
        }
        return try {
            connection.query(mysqlQueryConfig(parsed.sql, params)).await<JsArray<JsAny?>>()
        } catch (e: Throwable) {
            throw sqlException(e.message ?: "MySQL query failed", sqlState = null, cause = e)
        }
    }

    override suspend fun <T> execute(sql: String, namedParameters: Map<String, Any?>, handler: (ResultSet) -> T): List<T> {
        val result = run(sql, namedParameters)
        val fields = mysqlFields(result)
        val columns = Array(fields.length) { fields[it]!!.name }
        val rows = mysqlRows(result)
        return List(rows.length) { handler(MySqlResultSet(rows[it]!!, columns)) }
    }

    override suspend fun <T> execute(sql: String, paramSource: SqlParameterSource, handler: (ResultSet) -> T): List<T> =
        execute(sql, paramSource.toMap(), handler)

    override suspend fun execute(sql: String, namedParameters: Map<String, Any?>): Long =
        mysqlAffected(run(sql, namedParameters)).toLong()

    override suspend fun execute(sql: String, paramSource: SqlParameterSource): Long =
        execute(sql, paramSource.toMap())

    override suspend fun executeUpdate(sql: String, namedParameters: Map<String, Any?>): Long =
        execute(sql, namedParameters)
}

private fun SqlParameterSource.toMap(): Map<String, Any?> =
    (parameterNames ?: emptyArray()).associateWith { getValue(it) }
