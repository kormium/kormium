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
 * `loadLibrary` is not available yet: the WASM builds Kormium currently consumes come from upstream
 * compiled with `SQLITE_OMIT_LOAD_EXTENSION`, so there is nothing to load into. Reaching it needs
 * Kormium's own Emscripten build with dynamic linking (ADR 0013, decision 7). Pragmas and probes
 * work today.
 */
internal class WasmSqliteConnectionScope(
    override val engine: SqliteEngine,
    private val runStatement: suspend (String) -> Unit,
    private val readScalar: suspend (String) -> String?,
) : SuspendSqliteConnectionScope {

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
