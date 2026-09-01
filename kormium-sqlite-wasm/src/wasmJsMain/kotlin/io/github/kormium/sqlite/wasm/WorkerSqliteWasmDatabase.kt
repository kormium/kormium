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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * An in-memory SQLite [SuspendDatabase] hosted in a single dedicated Worker. Compared to the two
 * sibling engines:
 *
 * - vs [SqliteWasmDatabase] (wa-sqlite, main thread): same single-connection/`Mutex` model and
 *   memory speed, but SQLite executes OFF the main thread — a long aggregate no longer freezes UI
 *   rendering while it runs. No persistence (`:memory:` only).
 * - vs [PooledSqliteWasmDatabase] (OPFS pool): no persistence and no reader concurrency, but also
 *   none of OPFS's costs — no lock traffic (measured to dominate fast indexed queries), no
 *   `GetSyncHandleError` failure mode under bursts, and no COOP/COEP hosting requirement (a plain
 *   dedicated Worker needs no cross-origin isolation).
 *
 * The right default for data that fits in memory and doesn't need to survive a reload.
 *
 * A read-only block ([io.github.kormium.suspendTransaction] with `readOnly = true`) is not wrapped
 * in BEGIN/COMMIT: the single connection plus the block-scoped [Mutex] already make interleaving
 * impossible, so the block is trivially serializable and the wrap would only add two `postMessage`
 * round trips. Note `readOnly` is consequently not *enforced* here (an accidental write inside a
 * read-only block is not rejected the way the OPFS pool's `query_only` readers reject it).
 */
public class WorkerSqliteWasmDatabase internal constructor(
    private val connection: WorkerConnection,
    override val config: KormiumConfig,
) : SuspendDatabase<Nothing> {

    override val writeListeners: WriteListeners = WriteListeners()
    override val dialect: SqliteDialect = SqliteDialect

    private val lifecycle = DatabaseLifecycle {
        // Graceful close is a suspending RPC round trip (see WorkerConnection.close); the
        // synchronous AutoCloseable contract can only kick it off, not await it.
        CoroutineScope(Job()).launch { connection.close() }
    }
    private val connectionLock = Mutex()

    override val isClosed: Boolean get() = lifecycle.isClosed

    private val executor = WorkerSqlExecutor(connection, SqliteDialect, StandardTypeMapper)

    override suspend fun <R> useConnection(
        transactional: Boolean,
        isolation: TransactionIsolation?,
        readOnly: Boolean,
        block: suspend (SuspendSqlExecutor) -> R,
    ): R {
        lifecycle.checkOpen()
        return connectionLock.withLock {
            if (!transactional || readOnly) return@withLock block(executor)
            executor.execute("BEGIN")
            try {
                block(executor).also { executor.execute("COMMIT") }
            } catch (e: Throwable) {
                withContext(NonCancellable) { runCatching { executor.execute("ROLLBACK") } }
                throw e
            }
        }
    }

    override fun close(): Unit = lifecycle.close()
}

/**
 * Opens a [WorkerSqliteWasmDatabase]: one in-memory SQLite connection in one dedicated Worker.
 * Browser only (the Worker bundle ships via `@kormium/sqlite-wasm-worker`); needs no COOP/COEP
 * headers and no OPFS — see the class doc for how it compares to the other two engines.
 */
public suspend fun createWorkerSqliteWasmDatabase(
    config: KormiumConfig = KormiumConfig(),
    options: SqliteOptions = SqliteOptions(),
): WorkerSqliteWasmDatabase {
    options.beforeOpen(perConnectionRegistration(SqliteEngine.SqliteWasm))
    val connection = WorkerConnection.open(opfsPath = null)
    try {
        options.suspendApplyTo(connection.optionsScope(SqliteEngine.SqliteWasm))
    } catch (e: Throwable) {
        runCatching { connection.close() }
        throw e
    }
    return WorkerSqliteWasmDatabase(connection, config)
}
/**
 * A [WasmSqliteConnectionScope] over one Worker-hosted connection, for [SqliteOptions].
 */
internal fun WorkerConnection.optionsScope(engine: SqliteEngine): WasmSqliteConnectionScope =
    WasmSqliteConnectionScope(
        engine = engine,
        runStatement = { sql -> execute(sql, emptyList()) },
        readScalar = { sql -> query(sql, emptyList()).rows.firstOrNull()?.firstOrNull()?.toString() },
    )

