@file:JsModule("wa-sqlite/src/sqlite-api.js")

package io.github.kormium.sqlite.wasm

/**
 * `Factory` is a NAMED export of wa-sqlite's `sqlite-api.js` — it wraps the Emscripten module in
 * the high-level [SQLiteAPI]. Named exports need `@file:JsModule` (a declaration-level `@JsModule`
 * binds the module's `default` export instead). This file may contain only external declarations.
 */
internal external fun Factory(module: JsAny): SQLiteAPI
