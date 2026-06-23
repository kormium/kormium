package io.github.kormium.mysql.node

import kotlin.js.Promise

/** One mysql2 connection. query() resolves to a `[rows, fields]` tuple (a JS array of length 2). */
internal external interface MySqlConnection : JsAny {
    fun query(config: JsAny): Promise<JsArray<JsAny?>>
    fun end(): Promise<JsAny?>
}

internal external interface MySqlField : JsAny {
    val name: String
}

/** Builds the mysql2 connection config. */
internal fun mysqlConfig(host: String, port: Int, database: String, user: String, password: String): JsAny =
    js("({ host: host, port: port, database: database, user: user, password: password })")

/** Query config: positional `?` params, rows returned as arrays (so reads are by index). */
internal fun mysqlQueryConfig(sql: String, params: JsArray<JsAny?>): JsAny =
    js("({ sql: sql, values: params, rowsAsArray: true })")

/** First element of the `[rows, fields]` tuple: the result rows (array of arrays for a SELECT). */
internal fun mysqlRows(result: JsArray<JsAny?>): JsArray<JsArray<JsAny?>> = js("result[0]")

/** Second element: the column metadata (empty for a non-SELECT). */
internal fun mysqlFields(result: JsArray<JsAny?>): JsArray<MySqlField> = js("result[1] || []")

/** Affected-row count from a write's ResultSetHeader (the first tuple element for non-SELECT). */
internal fun mysqlAffected(result: JsArray<JsAny?>): Double = js("Number(result[0] && result[0].affectedRows || 0)")

/** A fresh empty JS array to fill with bound parameters. */
internal fun newJsArray(): JsArray<JsAny?> = js("[]")

/** Appends one parameter (already reduced to text, or null) to a JS array. */
internal fun pushParam(array: JsArray<JsAny?>, value: JsString?) {
    js("array.push(value == null ? null : value)")
}

/**
 * Normalises a result cell to text: `Date` → ISO-8601, objects (json) → JSON, else `String(...)`.
 * SQL `NULL` stays `null`.
 */
internal fun cellText(value: JsAny?): String? =
    js("value == null ? null : (value instanceof Date ? value.toISOString() : (typeof value === 'object' ? JSON.stringify(value) : String(value)))")
