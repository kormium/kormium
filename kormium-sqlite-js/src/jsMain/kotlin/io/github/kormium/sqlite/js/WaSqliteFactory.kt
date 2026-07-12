package io.github.kormium.sqlite.js

import kotlin.js.Promise

/**
 * The Emscripten module factory — the DEFAULT export of wa-sqlite's async build. A
 * declaration-level `@JsModule` binds the module's `default` export (here, the factory function),
 * which returns a `Promise` of the initialised WASM module. That module is then handed to
 * [Factory] to get the [SQLiteAPI].
 */
@JsModule("wa-sqlite/dist/wa-sqlite-async.mjs")
internal external fun SQLiteESMFactory(config: Any? = definedExternally): Promise<Any>
