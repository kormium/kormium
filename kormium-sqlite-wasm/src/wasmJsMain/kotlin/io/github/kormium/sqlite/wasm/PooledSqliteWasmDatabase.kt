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
import kotlinx.coroutines.withContext

/**
 * A SQLite [SuspendDatabase] backed by a dedicated writer [WorkerConnection] plus a pool of
 * reader [WorkerConnection]s, all against the same OPFS-backed file via the `opfs-wl` VFS
 * (`kormium/sqlite-wasm-kt`) — real multi-connection concurrent reads, unlike
 * [SqliteWasmDatabase]'s single Mutex-guarded connection.
 *
 * Routing: only an explicit read-only **transaction** (`suspendTransaction(readOnly = true) { }`,
 * i.e. `readOnly == true` here) goes to the reader pool. Everything else — including plain
 * `suspendAutocommit { }` — goes to the writer, because `useConnection(transactional = false)`
 * doesn't distinguish a read from a single-statement write (see the interface doc: autocommit is
 * "the cheap path for reads / single statements"); routing all of it to readers would risk sending
 * an autocommit write to a connection this pool can't guarantee is writable. Wrap reads you want
 * pooled in `suspendTransaction(readOnly = true) { }`, not `suspendAutocommit { }`.
 */
public class PooledSqliteWasmDatabase internal constructor(
    private val writer: WorkerConnection,
    private val readers: List<WorkerConnection>,
    override val config: KormiumConfig,
) : SuspendDatabase<Nothing> {

    override val writeListeners: WriteListeners = WriteListeners()
    override val dialect: SqliteDialect = SqliteDialect

    private val lifecycle = DatabaseLifecycle {
        writer.terminate()
        readers.forEach { it.terminate() }
    }
    private var nextReader = 0

    override val isClosed: Boolean get() = lifecycle.isClosed

    override suspend fun <R> useConnection(
        transactional: Boolean,
        isolation: TransactionIsolation?,
        readOnly: Boolean,
        block: suspend (SuspendSqlExecutor) -> R,
    ): R {
        lifecycle.checkOpen()
        val connection = if (readOnly) pickReader() else writer
        val executor = WorkerSqlExecutor(connection, SqliteDialect, StandardTypeMapper)
        if (!transactional) return block(executor)
        executor.execute("BEGIN")
        if (readOnly) executor.execute("PRAGMA query_only=ON")
        return try {
            block(executor).also { executor.execute("COMMIT") }
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { executor.execute("ROLLBACK") } }
            throw e
        } finally {
            if (readOnly) runCatching { executor.execute("PRAGMA query_only=OFF") }
        }
    }

    /** Round-robins the reader pool; falls back to the writer if [readers] is empty
     *  (`readerPoolSize = 0`, i.e. the pool is opted out of but the API shape stays the same). */
    private fun pickReader(): WorkerConnection {
        if (readers.isEmpty()) return writer
        val connection = readers[nextReader % readers.size]
        nextReader++
        return connection
    }

    override fun close(): Unit = lifecycle.close()
}

/**
 * Opens a [PooledSqliteWasmDatabase]: one writer connection plus [readerPoolSize] reader
 * connections (each its own Worker), all against the OPFS file at [opfsPath] via the `opfs-wl`
 * VFS. Requires a browser Worker context to construct the pool from (OPFS access handles are
 * Worker-only) and — because `opfs-wl` needs `Atomics.waitAsync` coordination — the page must be
 * [cross-origin isolated](https://developer.mozilla.org/en-US/docs/Web/API/Window/crossOriginIsolated)
 * (`Cross-Origin-Opener-Policy: same-origin` + `Cross-Origin-Embedder-Policy: require-corp`
 * response headers); this was confirmed empirically — a plain static host without those headers
 * cannot open this database. See [createSqliteWasmDatabase] for the single-connection engine,
 * which has no such requirement.
 */
public suspend fun createPooledSqliteWasmDatabase(
    opfsPath: String,
    readerPoolSize: Int = 4,
    config: KormiumConfig = KormiumConfig(),
): PooledSqliteWasmDatabase {
    val writer = WorkerConnection.open(opfsPath)
    val readers = List(readerPoolSize) { WorkerConnection.open(opfsPath) }
    return PooledSqliteWasmDatabase(writer, readers, config)
}
