package io.github.kormium.sqlite.node

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

/**
 * A [SuspendSqlExecutor] over one better-sqlite3 [Database]. better-sqlite3 is synchronous, so the
 * suspend methods don't actually suspend — they call straight through. SQL rendering ([Dialect]) and
 * value conversion ([TypeMapper]) are the shared core seams; parsing/binding/reads come from
 * `kormium-wasm-driver` (`:name` → `?`, values bound as text — SQLite's affinity handles the rest).
 */
internal class NodeSqliteExecutor(
    private val db: Database,
    override val dialect: Dialect,
    override val typeMapper: TypeMapper,
) : SuspendSqlExecutor {

    override suspend fun <T> execute(sql: String, namedParameters: Map<String, Any?>, handler: (ResultSet) -> T): List<T> {
        val parsed = parseNamedParams(sql, QuestionMarker)
        val rows = try {
            bsAll(db, parsed.sql, bindTextParams(parsed.names, namedParameters, typeMapper))
        } catch (e: Throwable) {
            throw sqlException(e.message ?: "SQLite query failed", sqlState = null, cause = e)
        }
        if (rows.length == 0) return emptyList()
        val keys = rowKeys(rows[0])
        val columns = Array(keys.length) { keys[it]!!.toString() }
        return List(rows.length) { handler(TextResultSet(rowValues(rows[it]), columns)) }
    }

    override suspend fun <T> execute(sql: String, paramSource: SqlParameterSource, handler: (ResultSet) -> T): List<T> =
        execute(sql, paramSource.toMap(), handler)

    override suspend fun execute(sql: String, namedParameters: Map<String, Any?>): Long {
        val parsed = parseNamedParams(sql, QuestionMarker)
        return try {
            bsRun(db, parsed.sql, bindTextParams(parsed.names, namedParameters, typeMapper)).toLong()
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
