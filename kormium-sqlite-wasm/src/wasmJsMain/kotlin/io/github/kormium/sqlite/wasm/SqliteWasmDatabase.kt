package io.github.kormium.sqlite.wasm

import io.github.kormium.DatabaseLifecycle
import io.github.kormium.KormiumConfig
import io.github.kormium.SqliteDialect
import io.github.kormium.StandardTypeMapper
import io.github.kormium.SuspendSqlExecutor
import io.github.kormium.TransactionIsolation
import io.github.kormium.WriteListeners
import io.github.kormium.database.SuspendDatabase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A SQLite [SuspendDatabase] backed by wa-sqlite (SQLite compiled to WASM). Like the other web
 * engines it implements only the suspend hierarchy — there is no blocking `Database` on wasmJs.
 *
 * wa-sqlite is one embedded instance (one logical connection); a [Mutex] serialises
 * [useConnection] so a transaction's BEGIN…COMMIT cannot interleave with another's. SQLite has a
 * single effective isolation level, so a requested [TransactionIsolation] is ignored (as the
 * SqliteDialect already declares).
 */
class SqliteWasmDatabase internal constructor(
    private val api: SQLiteAPI,
    private val db: JsNumber,
    override val config: KormiumConfig,
) : SuspendDatabase<Nothing> {

    override val writeListeners: WriteListeners = WriteListeners()
    override val dialect = SqliteDialect

    private val lifecycle = DatabaseLifecycle { api.close(db) }
    private val connectionLock = Mutex()

    override val isClosed: Boolean get() = lifecycle.isClosed

    private val executor = WaSqliteExecutor(api, db, SqliteDialect, StandardTypeMapper)

    override suspend fun <R> useConnection(
        transactional: Boolean,
        isolation: TransactionIsolation?,
        readOnly: Boolean,
        block: suspend (SuspendSqlExecutor) -> R,
    ): R {
        lifecycle.checkOpen()
        return connectionLock.withLock {
            if (!transactional) return@withLock block(executor)
            executor.execute("BEGIN")
            if (readOnly) executor.execute("PRAGMA query_only=ON")
            try {
                block(executor).also { executor.execute("COMMIT") }
            } catch (e: Throwable) {
                withContext(NonCancellable) { runCatching { executor.execute("ROLLBACK") } }
                throw e
            } finally {
                if (readOnly) runCatching { executor.execute("PRAGMA query_only=OFF") }
            }
        }
    }

    override fun close() = lifecycle.close()
}

/**
 * Opens a SQLite database via wa-sqlite.
 *
 * @param dataDir `null` (default) opens an in-memory database (`:memory:`). A non-null name opens a
 *   database persisted to IndexedDB under that name (browser only) via wa-sqlite's
 *   `IDBBatchAtomicVFS`, so data survives a page reload.
 */
suspend fun createSqliteWasmDatabase(
    dataDir: String? = null,
    config: KormiumConfig = KormiumConfig(),
    // Advanced/Node: Emscripten module overrides passed to the wa-sqlite factory. In the browser the
    // factory fetches its `.wasm` itself; under Node (where fetch can't read file://) pass
    // `{ wasmBinary: <bytes> }` here so it loads the module without fetching.
    moduleConfig: JsAny? = null,
): SqliteWasmDatabase {
    val module = (if (moduleConfig == null) SQLiteESMFactory() else SQLiteESMFactory(moduleConfig)).await<JsAny>()
    val api = Factory(module)
    val flags = SQLITE_OPEN_CREATE or SQLITE_OPEN_READWRITE
    val db = when (dataDir) {
        null -> api.open_v2(":memory:", flags, null).await<JsNumber>()
        else -> {
            // Persist to IndexedDB: register the VFS under [dataDir] and open a database in it.
            // The VFS is async (Asyncify), which is why this engine uses the async wa-sqlite build.
            api.vfs_register(IDBBatchAtomicVFS(dataDir), makeDefault = false)
            api.open_v2(dataDir, flags, dataDir).await<JsNumber>()
        }
    }
    check(db.toInt() != 0) { "wa-sqlite open_v2 returned a null database handle (dataDir=$dataDir)" }
    return SqliteWasmDatabase(api, db, config)
}
