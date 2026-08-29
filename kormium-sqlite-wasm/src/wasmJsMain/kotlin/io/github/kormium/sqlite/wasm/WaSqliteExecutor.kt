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
 * (compile → bind → step → finalize), bridging each `Promise` to suspend with `await()`. SQL
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
        return withStatement(parsed.sql) { stmt ->
            api.bind_collection(stmt, bindTextParams(parsed.names, namedParameters, typeMapper))
            val names = api.column_names(stmt)
            val columns = Array(names.length) { names[it]!!.toString() }
            val out = ArrayList<T>()
            while (api.step(stmt).await<JsNumber>().toInt() == SQLITE_ROW) {
                out.add(handler(TextResultSet(api.row(stmt), columns)))
            }
            out
        } ?: emptyList()
    }

    override suspend fun <T> execute(sql: String, paramSource: SqlParameterSource, handler: (ResultSet) -> T): List<T> =
        execute(sql, paramSource.toMap(), handler)

    override suspend fun execute(sql: String, namedParameters: Map<String, Any?>): Long {
        val parsed = parseNamedParams(sql, QuestionMarker)
        return withStatement(parsed.sql) { stmt ->
            api.bind_collection(stmt, bindTextParams(parsed.names, namedParameters, typeMapper))
            while (api.step(stmt).await<JsNumber>().toInt() == SQLITE_ROW) { /* drain any rows */ }
            api.changes(db).toInt().toLong()
        } ?: 0L
    }

    override suspend fun execute(sql: String, paramSource: SqlParameterSource): Long =
        execute(sql, paramSource.toMap())

    override suspend fun executeUpdate(sql: String, namedParameters: Map<String, Any?>): Long =
        execute(sql, namedParameters)

    /**
     * Compiles [sql] and runs [block] on the statement it yields, or returns null when [sql] holds
     * no statement (kormium emits one per call, so the rest of the generator is drained by ending
     * it). The generator owns the SQL buffer in wasm memory and frees it on exit; `unscoped` leaves
     * the statement itself to us, so its `finalize` is awaited here and cannot outlive a `close`.
     */
    private suspend fun <T> withStatement(sql: String, block: suspend (JsNumber) -> T): T? {
        val statements = api.statements(db, sql, unscopedStatements())
        val first = try {
            statements.next().await<StatementStep>()
        } catch (e: Throwable) {
            throw sqlException("${e.message ?: "SQLite prepare failed"} [sql: ${sql.trim().take(160)}]", sqlState = null, cause = e)
        }
        val stmt = if (first.done) null else first.value
        if (stmt == null) {
            // Nothing compiled (empty or comment-only SQL): the generator has already run its cleanup.
            return null
        }
        return try {
            block(stmt)
        } finally {
            api.finalize(stmt).await<JsNumber>()
            statements.`return`().await<StatementStep>()
        }
    }
}

/** wa-sqlite's `statements` options — see [SQLiteAPI.statements] for why this engine passes it. */
private fun unscopedStatements(): JsAny = js("({ unscoped: true })")

private fun SqlParameterSource.toMap(): Map<String, Any?> =
    (parameterNames ?: emptyArray()).associateWith { getValue(it) }
