package io.github.kormium.postgres.node

import io.github.kormium.Dialect
import io.github.kormium.SqlParameterSource
import io.github.kormium.SuspendSqlExecutor
import io.github.kormium.TypeMapper
import io.github.kormium.resultset.ResultSet
import io.github.kormium.sqlException
import kotlinx.coroutines.await

/**
 * A [SuspendSqlExecutor] bound to one node-postgres [Client]. Drives the async driver and bridges
 * its `Promise` to suspend via `await()`. SQL rendering ([Dialect]) and value conversion
 * ([TypeMapper]) are the shared core seams; `:name` is rewritten to `$N` and values bound as text
 * (unspecified OID — the server infers the type, the libpq approach).
 */
internal class PgExecutor(
    private val client: Client,
    override val dialect: Dialect,
    override val typeMapper: TypeMapper,
) : SuspendSqlExecutor {

    private suspend fun run(sql: String, namedParameters: Map<String, Any?>): PgResult {
        val parsed = parseNamedParams(sql)
        val params = newJsArray()
        for (name in parsed.names) {
            require(namedParameters.containsKey(name)) { "No value supplied for parameter \"$name\"" }
            val mapped = typeMapper.toParameter(namedParameters[name])
            pushParam(params, mapped?.toString()?.toJsString())
        }
        return try {
            client.query(pgQueryConfig(parsed.sql, params)).await<PgResult>()
        } catch (e: Throwable) {
            throw sqlException(e.message ?: "Postgres query failed", sqlState = null, cause = e)
        }
    }

    override suspend fun <T> execute(sql: String, namedParameters: Map<String, Any?>, handler: (ResultSet) -> T): List<T> {
        val result = run(sql, namedParameters)
        val columns = Array(result.fields.length) { result.fields[it]!!.name }
        val rows = result.rows
        return List(rows.length) { handler(PgResultSet(rows[it]!!, columns)) }
    }

    override suspend fun <T> execute(sql: String, paramSource: SqlParameterSource, handler: (ResultSet) -> T): List<T> =
        execute(sql, paramSource.toMap(), handler)

    override suspend fun execute(sql: String, namedParameters: Map<String, Any?>): Long =
        run(sql, namedParameters).rowCount?.toDouble()?.toLong() ?: 0L

    override suspend fun execute(sql: String, paramSource: SqlParameterSource): Long =
        execute(sql, paramSource.toMap())

    override suspend fun executeUpdate(sql: String, namedParameters: Map<String, Any?>): Long =
        execute(sql, namedParameters)
}

private fun SqlParameterSource.toMap(): Map<String, Any?> =
    (parameterNames ?: emptyArray()).associateWith { getValue(it) }
