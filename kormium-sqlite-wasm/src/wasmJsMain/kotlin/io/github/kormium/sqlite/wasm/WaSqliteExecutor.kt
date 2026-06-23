package io.github.kormium.sqlite.wasm

import io.github.kormium.Dialect
import io.github.kormium.SqlParameterSource
import io.github.kormium.SuspendSqlExecutor
import io.github.kormium.TypeMapper
import io.github.kormium.resultset.ResultSet
import io.github.kormium.sqlException
import kotlinx.coroutines.await

/**
 * A [SuspendSqlExecutor] bound to one wa-sqlite database handle. Drives the async wa-sqlite C API
 * (prepare → bind → step → finalize), bridging each `Promise` to suspend with `await()`. SQL
 * rendering ([Dialect]) and value conversion ([TypeMapper]) are the shared core seams; binding is
 * positional (`:name` → `?`, values bound as text — SQLite's type affinity handles the rest).
 */
internal class WaSqliteExecutor(
    private val api: SQLiteAPI,
    private val db: JsNumber,
    override val dialect: Dialect,
    override val typeMapper: TypeMapper,
) : SuspendSqlExecutor {

    private fun bind(stmt: JsNumber, names: List<String>, namedParameters: Map<String, Any?>) {
        if (names.isEmpty()) return
        val params = newJsArray()
        for (name in names) {
            // A missing key is a typo, not an explicit null: fail fast instead of binding NULL.
            require(namedParameters.containsKey(name)) { "No value supplied for parameter \"$name\"" }
            val mapped = typeMapper.toParameter(namedParameters[name])
            pushParam(params, mapped?.toString()?.toJsString())
        }
        api.bind_collection(stmt, params)
    }

    override suspend fun <T> execute(sql: String, namedParameters: Map<String, Any?>, handler: (ResultSet) -> T): List<T> {
        val parsed = parseNamedParams(sql)
        val stmt = prepare(parsed.sql) ?: return emptyList()
        return try {
            bind(stmt, parsed.names, namedParameters)
            val columns = Array(api.column_names(stmt).length) { api.column_names(stmt)[it]!!.toString() }
            val out = ArrayList<T>()
            while (api.step(stmt).await<JsNumber>().toInt() == SQLITE_ROW) {
                out.add(handler(WaSqliteResultSet(api.row(stmt), columns)))
            }
            out
        } finally {
            api.finalize(stmt).await<JsNumber>()
        }
    }

    override suspend fun <T> execute(sql: String, paramSource: SqlParameterSource, handler: (ResultSet) -> T): List<T> =
        execute(sql, paramSource.toMap(), handler)

    override suspend fun execute(sql: String, namedParameters: Map<String, Any?>): Long {
        val parsed = parseNamedParams(sql)
        val stmt = prepare(parsed.sql) ?: return 0L
        return try {
            bind(stmt, parsed.names, namedParameters)
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
     * Compiles [sql] into a statement handle (or null for an empty/whitespace statement). The SQL
     * must be passed to prepare_v2 as a pointer in wasm memory, so it is copied in via str_new and
     * freed via str_finish once prepare_v2 has compiled it (the compiled statement is independent of
     * the source string). Single statement only, which is what korm emits per call.
     */
    private suspend fun prepare(sql: String): JsNumber? {
        val str = api.str_new(db, sql)
        return try {
            api.prepare_v2(db, api.str_value(str)).await<PreparedStatement?>()?.stmt
        } catch (e: Throwable) {
            val msg = e.message ?: "SQLite prepare failed"
            throw sqlException("$msg [sql: ${sql.trim().take(160)}]", sqlState = null, cause = e)
        } finally {
            api.str_finish(str)
        }
    }
}

private fun SqlParameterSource.toMap(): Map<String, Any?> =
    (parameterNames ?: emptyArray()).associateWith { getValue(it) }
