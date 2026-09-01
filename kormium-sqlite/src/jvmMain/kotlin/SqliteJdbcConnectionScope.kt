package io.github.kormium

import org.sqlite.SQLiteConnection
import java.sql.Connection

/**
 * [SqliteConnectionScope] over a sqlite-jdbc [Connection], handed to extensions while a pooled
 * connection is being prepared.
 */
internal class SqliteJdbcConnectionScope(private val conn: Connection) : SqliteConnectionScope {

    override val engine: SqliteEngine get() = SqliteEngine.Xerial

    override fun exec(sql: String) {
        conn.createStatement().use { it.execute(sql) }
    }

    override fun queryScalar(sql: String): String? = runCatching {
        conn.createStatement().use { statement ->
            statement.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }.getOrNull()

    /**
     * Enables extension loading only for the duration of the call. sqlite-jdbc exposes SQLite's
     * `sqlite3_enable_load_extension` (not the `db_config` variant the native driver uses), which
     * also arms the `load_extension()` SQL function — so it is turned straight back off, leaving
     * application SQL unable to load anything.
     */
    override fun loadLibrary(path: String, entryPoint: String?) {
        val db = conn.unwrap(SQLiteConnection::class.java).database
        db.enable_load_extension(true)
        try {
            val sql = if (entryPoint == null) "select load_extension(?)" else "select load_extension(?, ?)"
            conn.prepareStatement(sql).use { statement ->
                statement.setString(1, path)
                if (entryPoint != null) statement.setString(2, entryPoint)
                statement.executeQuery().use { it.next() }
            }
        } finally {
            db.enable_load_extension(false)
        }
    }
}
