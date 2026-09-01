package io.github.kormium.sqlite.js

import io.github.kormium.SqliteEngine
import io.github.kormium.SuspendSqliteConnectionScope

/**
 * [SuspendSqliteConnectionScope] over the Kotlin/JS wa-sqlite engine — the sibling of
 * `kormium-sqlite-wasm`'s scope. Suspend because wa-sqlite's IndexedDB VFS is asynchronous
 * (Asyncify), so there is no blocking call to build a [io.github.kormium.SqliteConnectionScope] on.
 *
 * `loadLibrary` goes through the engine that owns the Emscripten module and the database handle;
 * whether the build can actually load anything is probed there.
 */
internal class JsSqliteConnectionScope(
    private val runStatement: suspend (String) -> Unit,
    private val readScalar: suspend (String) -> String?,
    private val loader: suspend (String, String?) -> Unit,
) : SuspendSqliteConnectionScope {

    override val engine: SqliteEngine get() = SqliteEngine.WaSqlite

    override suspend fun exec(sql: String) {
        runStatement(sql)
    }

    override suspend fun queryScalar(sql: String): String? = runCatching { readScalar(sql) }.getOrNull()

    override suspend fun loadLibrary(path: String, entryPoint: String?) {
        loader(path, entryPoint)
    }
}
