package io.github.kormium.sqlite.node

import io.github.kormium.SqliteConnectionScope
import io.github.kormium.SqliteEngine

/**
 * [SqliteConnectionScope] over a better-sqlite3 `Database`. This engine has a single connection,
 * so "per connection" and "per database" coincide here.
 */
internal class NodeSqliteConnectionScope(private val db: Database) : SqliteConnectionScope {

    override val engine: SqliteEngine get() = SqliteEngine.BetterSqlite3

    override fun exec(sql: String) {
        db.exec(sql)
    }

    override fun queryScalar(sql: String): String? = runCatching { bsScalar(db, sql)?.toString() }.getOrNull()

    // better-sqlite3 enables extension loading on the connection itself, so there is no separate
    // enable/disable step to bracket the call with.
    override fun loadLibrary(path: String, entryPoint: String?) {
        if (entryPoint == null) db.loadExtension(path) else db.loadExtension(path, entryPoint)
    }
}
