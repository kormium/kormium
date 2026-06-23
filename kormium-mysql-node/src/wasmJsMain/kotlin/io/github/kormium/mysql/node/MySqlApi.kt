package io.github.kormium.mysql.node

import kotlin.js.Promise

/** A mysql2 connection pool: hands out [PoolConnection]s (one per pinned connection). */
internal external interface MyPool : JsAny {
    fun getConnection(): Promise<PoolConnection>
    fun end(): Promise<JsAny?>
}

/** One pinned connection borrowed from a [MyPool]; [release] returns it. query() → `[rows, fields]`. */
internal external interface PoolConnection : JsAny {
    fun query(config: JsAny): Promise<JsArray<JsAny?>>
    fun release()
}

internal external interface MySqlField : JsAny {
    val name: String
}

/** Builds the mysql2 pool config (`connectionLimit` caps the pool size). */
internal fun mysqlPoolConfig(host: String, port: Int, database: String, user: String, password: String, connectionLimit: Int): JsAny =
    js("({ host: host, port: port, database: database, user: user, password: password, connectionLimit: connectionLimit })")

/** Query config: positional `?` params, rows returned as arrays (so reads are by index). */
internal fun mysqlQueryConfig(sql: String, params: JsArray<JsAny?>): JsAny =
    js("({ sql: sql, values: params, rowsAsArray: true })")

/** First element of the `[rows, fields]` tuple: the result rows (array of arrays for a SELECT). */
internal fun mysqlRows(result: JsArray<JsAny?>): JsArray<JsArray<JsAny?>> = js("result[0]")

/** Second element: the column metadata (empty for a non-SELECT). */
internal fun mysqlFields(result: JsArray<JsAny?>): JsArray<MySqlField> = js("result[1] || []")

/** Affected-row count from a write's ResultSetHeader (the first tuple element for non-SELECT). */
internal fun mysqlAffected(result: JsArray<JsAny?>): Double = js("Number(result[0] && result[0].affectedRows || 0)")
