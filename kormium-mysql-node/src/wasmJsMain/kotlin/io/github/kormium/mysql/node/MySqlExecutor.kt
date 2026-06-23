package io.github.kormium.mysql.node

import io.github.kormium.Dialect
import io.github.kormium.SqlParameterSource
import io.github.kormium.SuspendSqlExecutor
import io.github.kormium.TypeMapper
import io.github.kormium.resultset.ResultSet
import io.github.kormium.sqlException
import io.github.kormium.wasm.driver.QuestionMarker
import io.github.kormium.wasm.driver.TextResultSet
import io.github.kormium.wasm.driver.bindTextParams
import io.github.kormium.wasm.driver.parseNamedParams
import kotlinx.coroutines.await

/**
 * A [SuspendSqlExecutor] bound to one mysql2 [MySqlConnection]. Drives the async driver and bridges
 * its `Promise` to suspend via `await()`. SQL rendering ([Dialect]) and value conversion
 * ([TypeMapper]) are the shared core seams; parsing/binding/reads come from `kormium-wasm-driver`
 * (`:name` → `?`, values bound as text).
 */
internal class MySqlExecutor(
    private val connection: PoolConnection,
    override val dialect: Dialect,
    override val typeMapper: TypeMapper,
) : SuspendSqlExecutor {

    private suspend fun run(sql: String, namedParameters: Map<String, Any?>): JsArray<JsAny?> {
        val parsed = parseNamedParams(sql, QuestionMarker)
        val params = bindTextParams(parsed.names, namedParameters, typeMapper)
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
        return List(rows.length) { handler(TextResultSet(rows[it]!!, columns)) }
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
