package io.github.kormium.postgres.node

/** Result of a node-postgres query with `rowMode: 'array'`: positional rows + column metadata. */
internal external interface PgResult : JsAny {
    val rows: JsArray<JsArray<JsAny?>>
    val fields: JsArray<PgField>
    val rowCount: JsNumber?
}

internal external interface PgField : JsAny {
    val name: String
}

/** Builds the node-postgres client config object. */
internal fun pgClientConfig(host: String, port: Int, database: String, user: String, password: String): JsAny =
    js("({ host: host, port: port, database: database, user: user, password: password })")

/** Builds a query config: positional rows (so reads are by index) bound to text params. */
internal fun pgQueryConfig(sql: String, params: JsArray<JsAny?>): JsAny =
    js("({ text: sql, values: params, rowMode: 'array' })")

/** A fresh empty JS array to fill with bound parameters. */
internal fun newJsArray(): JsArray<JsAny?> = js("[]")

/** Appends one parameter (already reduced to text, or null) to a JS array. */
internal fun pushParam(array: JsArray<JsAny?>, value: JsString?) {
    js("array.push(value == null ? null : value)")
}

/**
 * Normalises a result cell to the text form korm's text-based reads expect: `Date` → ISO-8601,
 * objects (json/jsonb) → JSON, everything else via `String(...)`. SQL `NULL` stays `null`.
 */
internal fun cellText(value: JsAny?): String? =
    js("value == null ? null : (value instanceof Date ? value.toISOString() : (typeof value === 'object' ? JSON.stringify(value) : String(value)))")
