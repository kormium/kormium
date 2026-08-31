package io.github.kormium

import cnames.structs.sqlite3
import cnames.structs.sqlite3_stmt
import csqlite.SQLITE_DBCONFIG_ENABLE_LOAD_EXTENSION
import csqlite.SQLITE_OK
import csqlite.SQLITE_ROW
import csqlite.sqlite3_column_text
import csqlite.sqlite3_db_config
import csqlite.sqlite3_errmsg
import csqlite.sqlite3_exec
import csqlite.sqlite3_extended_errcode
import csqlite.sqlite3_finalize
import csqlite.sqlite3_free
import csqlite.sqlite3_load_extension
import csqlite.sqlite3_prepare_v2
import csqlite.sqlite3_step
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value

/**
 * [SqliteConnectionScope] over a native `sqlite3*`, handed to extensions while a pooled connection
 * is being prepared.
 */
@OptIn(ExperimentalForeignApi::class)
internal class SqliteNativeConnectionScope(private val conn: CPointer<sqlite3>) : SqliteConnectionScope {

    override val engine: SqliteEngine get() = SqliteEngine.Native

    override fun exec(sql: String) {
        if (sqlite3_exec(conn, sql, null, null, null) != SQLITE_OK) {
            val code = sqlite3_extended_errcode(conn)
            throw sqliteException(sqlite3_errmsg(conn)?.toKString() ?: "SQLite error", code)
        }
    }

    override fun queryScalar(sql: String): String? = memScoped {
        val holder = alloc<CPointerVar<sqlite3_stmt>>()
        if (sqlite3_prepare_v2(conn, sql, -1, holder.ptr, null) != SQLITE_OK) return@memScoped null
        val stmt = holder.value ?: return@memScoped null
        try {
            if (sqlite3_step(stmt) != SQLITE_ROW) null
            else sqlite3_column_text(stmt, 0)?.reinterpret<ByteVar>()?.toKString()
        } finally {
            sqlite3_finalize(stmt)
        }
    }

    /**
     * Enables loading through `SQLITE_DBCONFIG_ENABLE_LOAD_EXTENSION` rather than
     * `sqlite3_enable_load_extension`, so only the C API is armed and the `load_extension()` SQL
     * function stays off — application SQL cannot load code. Turned off again immediately.
     */
    override fun loadLibrary(path: String, entryPoint: String?) {
        sqlite3_db_config(conn, SQLITE_DBCONFIG_ENABLE_LOAD_EXTENSION, 1, null)
        try {
            memScoped {
                val error = alloc<CPointerVar<ByteVar>>()
                if (sqlite3_load_extension(conn, path, entryPoint, error.ptr) != SQLITE_OK) {
                    val message = error.value?.toKString() ?: "cannot load extension"
                    sqlite3_free(error.value)
                    throw QueryException("failed to load SQLite extension '$path': $message")
                }
            }
        } finally {
            sqlite3_db_config(conn, SQLITE_DBCONFIG_ENABLE_LOAD_EXTENSION, 0, null)
        }
    }
}
