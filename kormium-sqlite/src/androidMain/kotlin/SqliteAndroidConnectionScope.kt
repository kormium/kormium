package io.github.kormium

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * [SqliteConnectionScope] over an androidx.sqlite connection.
 *
 * Loading a library *into this connection* is not possible here: androidx.sqlite never hands out
 * the underlying `sqlite3*`, so there is nothing to call `sqlite3_load_extension` on. Android's way
 * in is process-global registration instead — [SqliteRegistrationScope.registerLibrary] from
 * [SqliteExtension.beforeOpen], which Kormium implements with a small JNI shim. This method points
 * there rather than failing mutely.
 */
internal class SqliteAndroidConnectionScope(private val conn: SQLiteConnection) : SqliteConnectionScope {

    override val engine: SqliteEngine get() = SqliteEngine.AndroidX

    override fun exec(sql: String) {
        conn.execSQL(sql)
    }

    override fun queryScalar(sql: String): String? = runCatching {
        conn.prepare(sql).use { statement -> if (statement.step()) statement.getText(0) else null }
    }.getOrNull()

    override fun loadLibrary(path: String, entryPoint: String?): Nothing =
        throw SqliteExtensionUnsupportedException(
            extension = path,
            engine = SqliteEngine.AndroidX,
            message = "androidx.sqlite does not expose the sqlite3 handle, so an extension cannot " +
                "be loaded into a single connection. On Android, register it process-wide instead: " +
                "call registerLibrary(path, entryPoint) from beforeOpen().",
        )
}
