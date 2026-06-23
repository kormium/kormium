package io.github.kormium.sqlite.node

/**
 * better-sqlite3 is a synchronous SQLite for Node; its module default export is the `Database`
 * constructor. Statement methods (`run`/`all`) are variadic, so they are driven from the JS helpers
 * below rather than typed here. Parameter binding, the text ResultSet and the `:name` parser come
 * from `kormium-wasm-driver`.
 */
@JsModule("better-sqlite3")
external class Database(filename: String) : JsAny {
    /** Runs one or more statements without parameters (DDL / scripts). */
    fun exec(sql: String)
    fun close()
}

/** Prepares [sql], binds [params] positionally and returns all result rows (SELECT / RETURNING). */
internal fun bsAll(db: Database, sql: String, params: JsArray<JsAny?>): JsArray<JsAny?> =
    js("db.prepare(sql).all(...params)")

/** Prepares [sql], binds [params] positionally, runs it and returns the affected-row count. */
internal fun bsRun(db: Database, sql: String, params: JsArray<JsAny?>): Double =
    js("Number(db.prepare(sql).run(...params).changes)")

/** Column names of a result row object, in select order. */
internal fun rowKeys(row: JsAny?): JsArray<JsString> = js("Object.keys(row)")

/** Column values of a result row object, in select order (aligns positionally with [rowKeys]). */
internal fun rowValues(row: JsAny?): JsArray<JsAny?> = js("Object.values(row)")
