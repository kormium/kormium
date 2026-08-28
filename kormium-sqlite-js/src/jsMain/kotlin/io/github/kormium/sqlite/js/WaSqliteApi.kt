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
 * the I/O calls (`open_v2`/`step`/`finalize`/`close`), which we bridge to suspend.
 * Kotlin/JS counterpart of `kormium-sqlite-wasm`'s wasmJs `SQLiteAPI`.
 */
internal external interface SQLiteAPI {
    fun open_v2(filename: String, flags: Int, vfs: String?): Promise<Int>

    /**
     * Compiles [sql]. wa-sqlite 1.1 dropped the low-level `prepare_v2`/`str_*` trio, leaving this
     * async generator as the only compile path: it copies the SQL into wasm memory, then yields one
     * handle per statement in it and frees that memory once the generator ends. Pass
     * `{ unscoped: true }` ([StatementSequence]) so it leaves the statement itself to us — its own
     * cleanup fires `finalize` without awaiting it, and that has to be ordered before `close`.
     */
    fun statements(db: Int, sql: String, options: Any): StatementSequence

    fun bind_collection(stmt: Int, bindings: Array<Any?>): Int
    fun step(stmt: Int): Promise<Int>
    fun row(stmt: Int): Array<Any?>
    fun column_names(stmt: Int): Array<String>
    fun changes(db: Int): Int
    fun finalize(stmt: Int): Promise<Int>
    fun close(db: Int): Promise<Int>
    fun vfs_register(vfs: Any, makeDefault: Boolean): Int
}

/**
 * The async generator [SQLiteAPI.statements] returns, driven by hand as an async iterator: `next`
 * compiles and yields the next statement, `return` ends the generator early and runs its cleanup.
 */
internal external interface StatementSequence {
    fun next(): Promise<StatementStep>

    // Backticked so the JS name stays `return` — the async generator's own early-exit method.
    fun `return`(): Promise<StatementStep>
}

/** One [StatementSequence] result: `done` once the SQL holds no further statement. */
internal external interface StatementStep {
    val done: Boolean
    val value: Int?
}
