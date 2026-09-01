package io.github.kormium.sqlite.js

import io.github.kormium.SqliteEngine
import io.github.kormium.SqliteExtensionUnsupportedException
import io.github.kormium.SuspendSqliteConnectionScope

/**
 * [SuspendSqliteConnectionScope] over the Kotlin/JS wa-sqlite engine — the sibling of
 * `kormium-sqlite-wasm`'s scope. Suspend because wa-sqlite's IndexedDB VFS is asynchronous
 * (Asyncify), so there is no blocking call to build a [io.github.kormium.SqliteConnectionScope] on.
 *
 * `loadLibrary` is not available: the upstream WASM build is compiled with
 * `SQLITE_OMIT_LOAD_EXTENSION` (ADR 0013, decision 7). Pragmas and probes work today.
 */
internal class JsSqliteConnectionScope(
    private val runStatement: suspend (String) -> Unit,
    private val readScalar: suspend (String) -> String?,
) : SuspendSqliteConnectionScope {

    override val engine: SqliteEngine get() = SqliteEngine.WaSqlite

    override suspend fun exec(sql: String) {
        runStatement(sql)
    }

    override suspend fun queryScalar(sql: String): String? = runCatching { readScalar(sql) }.getOrNull()

    override suspend fun loadLibrary(path: String, entryPoint: String?): Nothing =
        throw SqliteExtensionUnsupportedException(
            extension = path,
            engine = engine,
            message = "loading a SQLite extension at runtime is not available on the $engine " +
                "engine: its WASM build comes from upstream and is compiled with " +
                "SQLITE_OMIT_LOAD_EXTENSION. An extension compiled into the engine still works.",
        )
}
