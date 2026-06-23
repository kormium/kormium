package io.github.kormium.sqlite.node

/**
 * better-sqlite3 is a synchronous SQLite for Node; its module default export is the `Database`
 * constructor. Statement methods (`run`/`all`) are variadic, so they are driven from the JS helpers
 * below rather than typed here.
 */
@JsModule("better-sqlite3")
external class Database(filename: String) : JsAny {
    /** Runs one or more statements without parameters (DDL / scripts). */
    fun exec(sql: String)
    fun close()
}

/** A fresh empty JS array to fill with bound parameters. */
internal fun newJsArray(): JsArray<JsAny?> = js("[]")

/** Appends one parameter (already reduced to text, or null) to a JS array. */
internal fun pushParam(array: JsArray<JsAny?>, value: JsString?) {
    js("array.push(value == null ? null : value)")
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

/**
 * Normalises a result cell to text for korm's text-based reads. better-sqlite3 returns JS-native
 * values; non-native korm types are stored as TEXT by the dialect, so `String(...)` recovers what
 * the read path expects. SQL `NULL` stays `null`.
 */
internal fun cellText(value: JsAny?): String? =
    js("value == null ? null : String(value)")
