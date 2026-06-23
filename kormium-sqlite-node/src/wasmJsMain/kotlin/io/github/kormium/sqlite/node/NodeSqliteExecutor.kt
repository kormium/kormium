package io.github.kormium.sqlite.node

import io.github.kormium.Dialect
import io.github.kormium.SqlParameterSource
import io.github.kormium.SuspendSqlExecutor
import io.github.kormium.TypeMapper
import io.github.kormium.resultset.ResultSet
import io.github.kormium.sqlException

/**
 * A [SuspendSqlExecutor] over one better-sqlite3 [Database]. better-sqlite3 is synchronous, so the
 * suspend methods don't actually suspend — they call straight through. SQL rendering ([Dialect]) and
 * value conversion ([TypeMapper]) are the shared core seams; binding is positional (`:name` → `?`,
 * values bound as text — SQLite's type affinity handles the rest).
 */
internal class NodeSqliteExecutor(
    private val db: Database,
    override val dialect: Dialect,
    override val typeMapper: TypeMapper,
) : SuspendSqlExecutor {

    private fun toParams(names: List<String>, namedParameters: Map<String, Any?>): JsArray<JsAny?> {
        val params = newJsArray()
        for (name in names) {
            // A missing key is a typo, not an explicit null: fail fast instead of binding NULL.
            require(namedParameters.containsKey(name)) { "No value supplied for parameter \"$name\"" }
            val mapped = typeMapper.toParameter(namedParameters[name])
            pushParam(params, mapped?.toString()?.toJsString())
        }
        return params
    }

    override suspend fun <T> execute(sql: String, namedParameters: Map<String, Any?>, handler: (ResultSet) -> T): List<T> {
        val parsed = parseNamedParams(sql)
        val rows = try {
            bsAll(db, parsed.sql, toParams(parsed.names, namedParameters))
        } catch (e: Throwable) {
            throw sqlException(e.message ?: "SQLite query failed", sqlState = null, cause = e)
        }
        if (rows.length == 0) return emptyList()
        val columns = Array(rowKeys(rows[0]).length) { rowKeys(rows[0])[it]!!.toString() }
        return List(rows.length) { handler(NodeSqliteResultSet(rowValues(rows[it]), columns)) }
    }

    override suspend fun <T> execute(sql: String, paramSource: SqlParameterSource, handler: (ResultSet) -> T): List<T> =
        execute(sql, paramSource.toMap(), handler)

    override suspend fun execute(sql: String, namedParameters: Map<String, Any?>): Long {
        val parsed = parseNamedParams(sql)
        return try {
            bsRun(db, parsed.sql, toParams(parsed.names, namedParameters)).toLong()
        } catch (e: Throwable) {
            throw sqlException(e.message ?: "SQLite statement failed", sqlState = null, cause = e)
        }
    }

    override suspend fun execute(sql: String, paramSource: SqlParameterSource): Long =
        execute(sql, paramSource.toMap())

    override suspend fun executeUpdate(sql: String, namedParameters: Map<String, Any?>): Long =
        execute(sql, namedParameters)
}

private fun SqlParameterSource.toMap(): Map<String, Any?> =
    (parameterNames ?: emptyArray()).associateWith { getValue(it) }
