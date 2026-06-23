@file:JsModule("mysql2/promise")

package io.github.kormium.mysql.node

import kotlin.js.Promise

/** `createConnection` — the promise-API entry point of mysql2; a named export, hence `@file:JsModule`. */
internal external fun createConnection(config: JsAny): Promise<MySqlConnection>
