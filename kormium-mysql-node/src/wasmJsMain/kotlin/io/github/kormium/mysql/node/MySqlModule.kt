@file:JsModule("mysql2/promise")

package io.github.kormium.mysql.node

/** `createPool` — the promise-API pool factory of mysql2; a named export, hence `@file:JsModule`. */
internal external fun createPool(config: JsAny): MyPool
