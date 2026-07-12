package io.github.kormium.sqlite.js

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
 * rendering ([Dialect]) and value conversion ([TypeMapper]) are the shared core seams; `:name` is
 * rewritten to `?` and values bind as text. Kotlin/JS counterpart of `kormium-sqlite-wasm`'s
 * `WaSqliteExecutor`.
 */
internal class WaSqliteExecutor(
    private val api: SQLiteAPI,
    private val db: Int,
    override val dialect: Dialect,
    override val typeMapper: TypeMapper,
) : SuspendSqlExecutor {

    override suspend fun <T> execute(sql: String, namedParameters: Map<String, Any?>, handler: (ResultSet) -> T): List<T> {
        val parsed = parseNamedParams(sql)
        val stmt = prepare(parsed.sql) ?: return emptyList()
        return try {
            api.bind_collection(stmt, bindTextParams(parsed.names, namedParameters, typeMapper))
            val columns = api.column_names(stmt)
            val out = ArrayList<T>()
            while (api.step(stmt).await() == SQLITE_ROW) {
                out.add(handler(TextResultSet(api.row(stmt), columns)))
            }
            out
        } finally {
            api.finalize(stmt).await()
        }
    }

    override suspend fun <T> execute(sql: String, paramSource: SqlParameterSource, handler: (ResultSet) -> T): List<T> =
        execute(sql, paramSource.toMap(), handler)

    override suspend fun execute(sql: String, namedParameters: Map<String, Any?>): Long {
        val parsed = parseNamedParams(sql)
        val stmt = prepare(parsed.sql) ?: return 0L
        return try {
            api.bind_collection(stmt, bindTextParams(parsed.names, namedParameters, typeMapper))
            while (api.step(stmt).await() == SQLITE_ROW) { /* drain any rows */ }
            api.changes(db).toLong()
        } finally {
            api.finalize(stmt).await()
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
    private suspend fun prepare(sql: String): Int? {
        val str = api.str_new(db, sql)
        return try {
            api.prepare_v2(db, api.str_value(str)).await()?.stmt
        } catch (e: Throwable) {
            throw sqlException("${e.message ?: "SQLite prepare failed"} [sql: ${sql.trim().take(160)}]", sqlState = null, cause = e)
        } finally {
            api.str_finish(str)
        }
    }
}

/**
 * Reduces named parameters to a positional JS array in [names] order. Most values bind as text (the
 * server/affinity resolves the type); a [ByteArray] binds as native binary. A missing key is a typo,
 * not an explicit null, so it fails fast. The returned Kotlin [Array] is a JS array at runtime, which
 * is what wa-sqlite's `bind_collection` iterates.
 */
private fun bindTextParams(names: List<String>, namedParameters: Map<String, Any?>, typeMapper: TypeMapper): Array<Any?> =
    Array(names.size) { i ->
        val name = names[i]
        require(namedParameters.containsKey(name)) { "No value supplied for parameter \"$name\"" }
        when (val mapped = typeMapper.toParameter(namedParameters[name])) {
            null -> null
            is ByteArray -> byteArrayToBinary(mapped)
            else -> mapped.toString() // a Kotlin/JS String is already a JS string
        }
    }

private fun SqlParameterSource.toMap(): Map<String, Any?> =
    (parameterNames ?: emptyArray()).associateWith { getValue(it) }
