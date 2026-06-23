package io.github.kormium.sqlite.wasm

import kotlin.js.Promise

// SQLite C result/flag codes (stable ABI constants).
internal const val SQLITE_OK = 0
internal const val SQLITE_ROW = 100
internal const val SQLITE_DONE = 101
internal const val SQLITE_OPEN_READWRITE = 0x00000002
internal const val SQLITE_OPEN_CREATE = 0x00000004

/**
 * The wa-sqlite API object returned by `Factory(module)`. Database and statement handles are JS
 * numbers (C pointers), passed around opaquely as [JsNumber]. The async build returns a `Promise`
 * from the I/O calls (`open_v2`/`prepare_v2`/`step`/`finalize`/`close`), which we bridge to suspend.
 * Parameter binding, the text ResultSet and the `:name` parser come from `kormium-wasm-driver`.
 */
internal external interface SQLiteAPI : JsAny {
    fun open_v2(filename: String, flags: Int, vfs: String?): Promise<JsNumber>

    // prepare_v2 takes a POINTER to the SQL in wasm memory, not a JS string. wa-sqlite's str_*
    // helpers move a JS string into wasm memory: str_new allocates and copies, str_value yields the
    // pointer, str_finish frees it. (The high-level statements() generator does this internally.)
    fun str_new(db: JsNumber, s: String): JsNumber
    fun str_value(str: JsNumber): JsNumber
    fun str_finish(str: JsNumber)
    fun prepare_v2(db: JsNumber, sqlPointer: JsNumber): Promise<PreparedStatement?>

    fun bind_collection(stmt: JsNumber, bindings: JsArray<JsAny?>): Int
    fun step(stmt: JsNumber): Promise<JsNumber>
    fun row(stmt: JsNumber): JsArray<JsAny?>
    fun column_names(stmt: JsNumber): JsArray<JsString>
    fun finalize(stmt: JsNumber): Promise<JsNumber>
    fun changes(db: JsNumber): JsNumber
    fun close(db: JsNumber): Promise<JsNumber>
    fun vfs_register(vfs: JsAny, makeDefault: Boolean): Int
}

/** Result of `prepare_v2`: the compiled statement handle plus a pointer to any remaining SQL. */
internal external interface PreparedStatement : JsAny {
    val stmt: JsNumber
}
