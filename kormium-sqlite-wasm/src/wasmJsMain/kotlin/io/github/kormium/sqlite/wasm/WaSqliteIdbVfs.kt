@file:JsModule("wa-sqlite/src/examples/IDBBatchAtomicVFS.js")

package io.github.kormium.sqlite.wasm

/**
 * wa-sqlite's IndexedDB-backed VFS — a NAMED export, hence `@file:JsModule`. Its asynchronous file
 * I/O relies on Asyncify, so it only works with the async wa-sqlite build (the one this engine
 * uses). Construct it with the IndexedDB database name, then register it via
 * [SQLiteAPI.vfs_register] and pass its name to `open_v2` to persist a database to the browser.
 */
external class IDBBatchAtomicVFS(idbDatabaseName: String) : JsAny
