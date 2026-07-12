package io.github.kormium.sqlite.js

import kotlin.js.Promise

// SQLite C result/flag codes (stable ABI constants).
internal const val SQLITE_OK = 0
internal const val SQLITE_ROW = 100
internal const val SQLITE_DONE = 101
internal const val SQLITE_OPEN_READWRITE = 0x00000002
internal const val SQLITE_OPEN_CREATE = 0x00000004

/**
 * The wa-sqlite API object returned by `Factory(module)`. Database and statement handles are JS
 * numbers (C pointers) passed around opaquely as [Int]. The async build returns a `Promise` from
 * the I/O calls (`open_v2`/`prepare_v2`/`step`/`finalize`/`close`), which we bridge to suspend.
 * Kotlin/JS counterpart of `kormium-sqlite-wasm`'s wasmJs `SQLiteAPI`.
 */
internal external interface SQLiteAPI {
    fun open_v2(filename: String, flags: Int, vfs: String?): Promise<Int>

    // prepare_v2 takes a POINTER to the SQL in wasm memory, not a JS string. wa-sqlite's str_*
    // helpers move a JS string into wasm memory: str_new allocates and copies, str_value yields the
    // pointer, str_finish frees it.
    fun str_new(db: Int, s: String): Int
    fun str_value(str: Int): Int
    fun str_finish(str: Int)
    fun prepare_v2(db: Int, sqlPointer: Int): Promise<PreparedStatement?>

    fun bind_collection(stmt: Int, bindings: Array<Any?>): Int
    fun step(stmt: Int): Promise<Int>
    fun row(stmt: Int): Array<Any?>
    fun column_names(stmt: Int): Array<String>
    fun changes(db: Int): Int
    fun finalize(stmt: Int): Promise<Int>
    fun close(db: Int): Promise<Int>
    fun vfs_register(vfs: Any, makeDefault: Boolean): Int
}

/** Result of `prepare_v2`: the compiled statement handle plus a pointer to any remaining SQL. */
internal external interface PreparedStatement {
    val stmt: Int
}
