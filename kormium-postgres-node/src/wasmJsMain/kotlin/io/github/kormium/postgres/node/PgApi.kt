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

/**
 * A node-postgres connection pool. Constructed via [newPgPool] rather than an `@JsModule` external
 * class: `pg.Pool` is a lazy getter on the CommonJS module, which the static named-import binding
 * resolves to a non-constructor — `require('pg').Pool` evaluates it at call time instead.
 */
internal external interface Pool : JsAny {
    fun connect(): kotlin.js.Promise<PoolClient>
    fun end(): kotlin.js.Promise<JsAny?>
}

/** One pinned connection borrowed from a [Pool]; [release] returns it to the pool. */
internal external interface PoolClient : JsAny {
    fun query(config: JsAny): kotlin.js.Promise<PgResult>
    fun release()
}

/** Constructs a node-postgres `Pool` (`max` caps the pool size). */
internal fun newPgPool(host: String, port: Int, database: String, user: String, password: String, max: Int): Pool =
    js("new (require('pg').Pool)({ host: host, port: port, database: database, user: user, password: password, max: max })")

/** Builds a query config: positional rows (so reads are by index) bound to text params. */
internal fun pgQueryConfig(sql: String, params: JsArray<JsAny?>): JsAny =
    js("({ text: sql, values: params, rowMode: 'array' })")
