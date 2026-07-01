package io.github.kormium.sqlite.wasm

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
 * A [SuspendSqlExecutor] bound to one wa-sqlite database handle. Drives the async wa-sqlite C API
 * (prepare → bind → step → finalize), bridging each `Promise` to suspend with `await()`. SQL
 * rendering ([Dialect]) and value conversion ([TypeMapper]) are the shared core seams;
 * parsing/binding/reads come from `kormium-wasm-driver` (`:name` → `?`, bound as text).
 */
internal class WaSqliteExecutor(
    private val api: SQLiteAPI,
    private val db: JsNumber,
    override val dialect: Dialect,
    override val typeMapper: TypeMapper,
) : SuspendSqlExecutor {

    override suspend fun <T> execute(sql: String, namedParameters: Map<String, Any?>, handler: (ResultSet) -> T): List<T> {
        val parsed = parseNamedParams(sql, QuestionMarker)
        val stmt = prepare(parsed.sql) ?: return emptyList()
        return try {
            api.bind_collection(stmt, bindTextParams(parsed.names, namedParameters, typeMapper))
            val names = api.column_names(stmt)
            val columns = Array(names.length) { names[it]!!.toString() }
            val out = ArrayList<T>()
            while (api.step(stmt).await<JsNumber>().toInt() == SQLITE_ROW) {
                out.add(handler(TextResultSet(api.row(stmt), columns)))
            }
            out
        } finally {
            api.finalize(stmt).await<JsNumber>()
        }
    }

    override suspend fun <T> execute(sql: String, paramSource: SqlParameterSource, handler: (ResultSet) -> T): List<T> =
        execute(sql, paramSource.toMap(), handler)

    override suspend fun execute(sql: String, namedParameters: Map<String, Any?>): Long {
        val parsed = parseNamedParams(sql, QuestionMarker)
        val stmt = prepare(parsed.sql) ?: return 0L
        return try {
            api.bind_collection(stmt, bindTextParams(parsed.names, namedParameters, typeMapper))
            while (api.step(stmt).await<JsNumber>().toInt() == SQLITE_ROW) { /* drain any rows */ }
            api.changes(db).toInt().toLong()
        } finally {
            api.finalize(stmt).await<JsNumber>()
        }
    }

    override suspend fun execute(sql: String, paramSource: SqlParameterSource): Long =
        execute(sql, paramSource.toMap())

    override suspend fun executeUpdate(sql: String, namedParameters: Map<String, Any?>): Long =
        execute(sql, namedParameters)

    /**
     * Compiles [sql] into a statement handle (or null for an empty statement). The SQL must be passed
     * to prepare_v2 as a pointer in wasm memory, so it is copied in via str_new and freed via
     * str_finish once prepare_v2 has compiled it. Single statement, which is what korm emits per call.
     */
    private suspend fun prepare(sql: String): JsNumber? {
        val str = api.str_new(db, sql)
        return try {
            api.prepare_v2(db, api.str_value(str)).await<PreparedStatement?>()?.stmt
        } catch (e: Throwable) {
            throw sqlException("${e.message ?: "SQLite prepare failed"} [sql: ${sql.trim().take(160)}]", sqlState = null, cause = e)
        } finally {
            api.str_finish(str)
        }
    }
}

private fun SqlParameterSource.toMap(): Map<String, Any?> =
    (parameterNames ?: emptyArray()).associateWith { getValue(it) }
