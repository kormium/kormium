package io.github.kormium.sqlite.wasm

import io.github.kormium.SqliteEngine
import io.github.kormium.SqliteExtensionUnsupportedException
import io.github.kormium.SuspendSqliteConnectionScope

/**
 * [SuspendSqliteConnectionScope] over any of the browser engines. The suspend form is not a
 * stylistic choice: wa-sqlite's IndexedDB VFS is asynchronous (Asyncify) and the Worker engines are
 * reached by `postMessage`, so there is no blocking call to build a
 * [io.github.kormium.SqliteConnectionScope] on.
 *
 * Driven through two lambdas rather than a shared executor type, because the three engines reach
 * SQLite differently (a `WaSqliteExecutor` here, a `WorkerConnection` there) and all this scope
 * needs from them is "run a statement" and "read one value".
 *
 * `loadLibrary` is supplied by the engine that has something to load into — today the wa-sqlite
 * one, which owns the Emscripten module and the database handle. The Worker-hosted engines run
 * SQLite in another thread and hand out neither, so they leave it null and refuse.
 */
internal class WasmSqliteConnectionScope(
    override val engine: SqliteEngine,
    private val runStatement: suspend (String) -> Unit,
    private val readScalar: suspend (String) -> String?,
    private val loader: (suspend (String, String?) -> Unit)? = null,
) : SuspendSqliteConnectionScope {

    override suspend fun exec(sql: String) {
        runStatement(sql)
    }

    override suspend fun queryScalar(sql: String): String? = runCatching { readScalar(sql) }.getOrNull()

    override suspend fun loadLibrary(path: String, entryPoint: String?) {
        val load = loader ?: throw SqliteExtensionUnsupportedException(
            extension = path,
            engine = engine,
            message = "the $engine engine runs SQLite in a Worker and exposes neither the module " +
                "nor the database handle, so an extension cannot be loaded into it at runtime. " +
                "Use an engine build with the extension compiled in.",
        )
        load(path, entryPoint)
    }
}
