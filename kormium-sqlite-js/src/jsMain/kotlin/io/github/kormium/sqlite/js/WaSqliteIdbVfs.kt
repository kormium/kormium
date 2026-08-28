@file:JsModule("wa-sqlite/src/examples/IDBBatchAtomicVFS.js")

package io.github.kormium.sqlite.js

import kotlin.js.Promise

/**
 * wa-sqlite's IndexedDB-backed VFS — a NAMED export, hence `@file:JsModule`. Its asynchronous file
 * I/O relies on Asyncify, so it only works with the async wa-sqlite build (the one this engine
 * uses). Construct it with the IndexedDB database name and the Emscripten module, await [isReady]
 * (opening the IndexedDB database is itself asynchronous), then register it via
 * [SQLiteAPI.vfs_register] and pass its name to `open_v2` to persist a database to the browser.
 */
internal external class IDBBatchAtomicVFS(idbDatabaseName: String, module: Any) {
    /** Resolves once the VFS has opened its IndexedDB database and is safe to register. */
    fun isReady(): Promise<Any?>
}
