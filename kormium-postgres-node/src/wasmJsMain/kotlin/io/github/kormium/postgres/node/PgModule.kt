@file:JsModule("pg")

package io.github.kormium.postgres.node

import kotlin.js.Promise

/**
 * The node-postgres `Client` — a NAMED export of the `pg` package, hence `@file:JsModule`. One
 * client is one connection; queries run over the Postgres wire protocol with `$N` placeholders.
 */
internal external class Client(config: JsAny) : JsAny {
    fun connect(): Promise<JsAny?>
    fun query(config: JsAny): Promise<PgResult>
    fun end(): Promise<JsAny?>
}
