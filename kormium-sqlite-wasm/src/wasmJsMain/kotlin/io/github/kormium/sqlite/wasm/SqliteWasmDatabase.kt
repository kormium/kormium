package io.github.kormium.sqlite.wasm

import io.github.kormium.DatabaseLifecycle
import io.github.kormium.KormiumConfig
import io.github.kormium.SqliteDialect
import io.github.kormium.SqliteEngine
import io.github.kormium.perConnectionRegistration
import io.github.kormium.SqliteOptions
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
public class SqliteWasmDatabase internal constructor(
    private val api: SQLiteAPI,
    private val db: JsNumber,
    override val config: KormiumConfig,
) : SuspendDatabase<Nothing> {

    override val writeListeners: WriteListeners = WriteListeners()
    override val dialect: SqliteDialect = SqliteDialect

    private val lifecycle = DatabaseLifecycle { api.close(db) }
    private val connectionLock = Mutex()

    override val isClosed: Boolean get() = lifecycle.isClosed

    private val executor = WaSqliteExecutor(api, db, SqliteDialect, StandardTypeMapper)

    /** Applies the caller's [SqliteOptions] to this connection; see the factory below. */
    internal suspend fun applyOptions(options: SqliteOptions) {
        options.suspendApplyTo(
            WasmSqliteConnectionScope(
                engine = SqliteEngine.WaSqlite,
                runStatement = { sql -> executor.execute(sql, emptyMap()) },
                readScalar = { sql -> executor.execute(sql, emptyMap()) { it.getString(0) }.firstOrNull() },
            ),
        )
    }

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

    override fun close(): Unit = lifecycle.close()
}

/**
 * Opens a SQLite database via wa-sqlite.
 *
 * @param options extensions to install and pragmas to apply on the connection. Runtime extension
 *   loading needs a WASM build that supports it; the default one does not (see [engine]).
 * @param engine which WASM SQLite build to run on. Defaults to the wa-sqlite build Kormium ships;
 *   pass another to use an engine with extensions compiled in — in the browser an extension is a
 *   different build, not a file loaded into the existing one.
 * @param dataDir `null` (default) opens an in-memory database (`:memory:`). A non-null name opens a
 *   database persisted to IndexedDB under that name (browser only) via wa-sqlite's
 *   `IDBBatchAtomicVFS`, so data survives a page reload.
 */
public suspend fun createSqliteWasmDatabase(
    dataDir: String? = null,
    config: KormiumConfig = KormiumConfig(),
    // Advanced/Node: Emscripten module overrides passed to the wa-sqlite factory. In the browser the
    // factory fetches its `.wasm` itself; under Node (where fetch can't read file://) pass
    // `{ wasmBinary: <bytes> }` here so it loads the module without fetching.
    moduleConfig: JsAny? = null,
    options: SqliteOptions = SqliteOptions(),
    engine: SqliteWasmEngine = SqliteWasmEngine.Default,
): SqliteWasmDatabase {
    // Rejects an extension this engine cannot install before anything is opened.
    options.beforeOpen(perConnectionRegistration(SqliteEngine.WaSqlite))
    val module = engine.instantiate(moduleConfig).await<JsAny>()
    val api = Factory(module)
    val flags = SQLITE_OPEN_CREATE or SQLITE_OPEN_READWRITE
    val db = when (dataDir) {
        null -> api.open_v2(":memory:", flags, null).await<JsNumber>()
        else -> {
            // Persist to IndexedDB: register the VFS under [dataDir] and open a database in it.
            // The VFS is async (Asyncify), which is why this engine uses the async wa-sqlite build,
            // and its own IndexedDB setup has to finish before SQLite may reach it.
            val vfs = IDBBatchAtomicVFS(dataDir, module)
            vfs.isReady().await<JsAny?>()
            api.vfs_register(vfs, makeDefault = false)
            api.open_v2(dataDir, flags, dataDir).await<JsNumber>()
        }
    }
    check(db.toInt() != 0) { "wa-sqlite open_v2 returned a null database handle (dataDir=$dataDir)" }
    val database = SqliteWasmDatabase(api, db, config)
    // A connection that cannot be prepared must not be handed out: close it and let the failure
    // surface here rather than at the first query.
    try {
        database.applyOptions(options)
    } catch (e: Throwable) {
        runCatching { database.close() }
        throw e
    }
    return database
}
