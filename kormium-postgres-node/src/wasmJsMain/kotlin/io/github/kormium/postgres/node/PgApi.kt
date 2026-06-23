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
