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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A SQLite [SuspendDatabase] backed by a single serialized writer [WorkerConnection] plus a pool of
 * reader [WorkerConnection]s, all against the same OPFS-backed file via the `opfs-wl` VFS
 * (`kormium/sqlite-wasm-kt`). Reads run lock-free across the reader Workers (the concurrency this
 * engine exists for); writes are serialized on the one writer connection by [writerLock], the same
 * whole-block guarantee [SqliteWasmDatabase] gives with its `Mutex`.
 *
 * Routing: only an explicit read-only **transaction** (`suspendTransaction(readOnly = true) { }`,
 * i.e. `readOnly == true` here) goes to the reader pool. Everything else — including plain
 * `suspendAutocommit { }` — goes to the writer, because `useConnection(transactional = false)`
 * doesn't distinguish a read from a single-statement write (see the interface doc: autocommit is
 * "the cheap path for reads / single statements"); routing all of it to readers would risk sending
 * an autocommit write to a connection this pool can't guarantee is writable. Wrap reads you want
 * pooled in `suspendTransaction(readOnly = true) { }`, not `suspendAutocommit { }`.
 *
 * To reopen the same `opfsPath` afterwards, prefer [closeAndAwait] over [close] — see its doc.
 */
public class PooledSqliteWasmDatabase internal constructor(
    private val writer: WorkerConnection,
    private val readers: List<WorkerConnection>,
    override val config: KormiumConfig,
) : SuspendDatabase<Nothing> {

    override val writeListeners: WriteListeners = WriteListeners()
    override val dialect: SqliteDialect = SqliteDialect

    private val allConnections = listOf(writer) + readers

    // Serializes every block that runs on the single writer connection. Readers are NOT locked —
    // concurrency across reader Workers is the whole point. Without this, two concurrent write
    // blocks interleave their BEGIN/COMMIT on one connection and the second BEGIN fails ("cannot
    // start a transaction within a transaction"), or their statements merge into one transaction.
    private val writerLock = Mutex()

    // The lifecycle flag is decoupled from connection teardown so that both the synchronous [close]
    // (fire-and-forget) and the suspending [closeAndAwait] (awaited) can drive teardown exactly once.
    // wasmJs is single-threaded, so a plain flag is a safe once-guard (no preemption check→set).
    private val lifecycle = DatabaseLifecycle { }
    private var teardownStarted = false
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
        // Any block on the writer connection takes the lock — including a readOnly block that fell
        // back to the writer because readers is empty (readerPoolSize = 0), which must not interleave
        // with a concurrent write on that same connection. True reader connections stay lock-free.
        return if (connection === writer) {
            writerLock.withLock { runBlock(connection, transactional, readOnly, block) }
        } else {
            runBlock(connection, transactional, readOnly, block)
        }
    }

    private suspend fun <R> runBlock(
        connection: WorkerConnection,
        transactional: Boolean,
        readOnly: Boolean,
        block: suspend (SuspendSqlExecutor) -> R,
    ): R {
        val executor = WorkerSqlExecutor(connection, SqliteDialect, StandardTypeMapper)
        // A readOnly block that fell back to the writer (readerPoolSize = 0) has no query_only reader
        // to protect it, so guard it read-only for the block's duration — the writer has query_only
        // OFF, and this restores the per-block toggle the reader connections get once at open.
        if (readOnly && connection === writer) {
            executor.execute("PRAGMA query_only=ON")
            return try {
                block(executor)
            } finally {
                withContext(NonCancellable) { runCatching { executor.execute("PRAGMA query_only=OFF") } }
            }
        }
        // Read-only blocks skip BEGIN/COMMIT entirely: each statement is one postMessage round
        // trip to the Worker, so the wrap costs two extra round trips per block — and each round
        // trip's reply waits in the MAIN thread's event queue, which under active UI rendering
        // (the exact dashboard scenario the pool exists for) can dwarf the query itself. An
        // autocommit SELECT sees a consistent snapshot on its own; the trade-off is that MULTIPLE
        // statements in one readOnly block get per-statement snapshots, not one shared snapshot
        // (a writer's commit may become visible between them).
        if (!transactional || readOnly) return block(executor)
        executor.execute("BEGIN")
        return try {
            block(executor).also { executor.execute("COMMIT") }
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { executor.execute("ROLLBACK") } }
            throw e
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

    /**
     * Closes without awaiting: [AutoCloseable]'s contract is synchronous, so the graceful,
     * handle-releasing close of each Worker (see [WorkerConnection.close]) is kicked off on a
     * detached scope and not awaited. Prefer [closeAndAwait] when you will reopen the same
     * `opfsPath` — this path does not guarantee the OPFS access handles are released before it
     * returns (or at all, if the enclosing scope dies first).
     */
    override fun close() {
        lifecycle.close()
        if (!teardownStarted) {
            teardownStarted = true
            CoroutineScope(Job()).launch { closeAllConnections() }
        }
    }

    /**
     * Closes and **awaits** graceful release of every connection's OPFS access handle before
     * returning. An abrupt `terminate()` can leave the handle held past the Worker's death, so the
     * next `createPooledSqliteWasmDatabase` on the same `opfsPath` would fail to acquire its lock
     * (`xLock` / `GetSyncHandleError`); awaiting the graceful close here prevents that. Idempotent,
     * and interchangeable with [close] for the flag/`isClosed` — but only this variant is safe to
     * sequence a reopen after. (If [close] already started the detached teardown, this returns
     * without a second teardown; call [closeAndAwait] rather than [close] when a reopen will follow.)
     */
    public suspend fun closeAndAwait() {
        lifecycle.close()
        if (!teardownStarted) {
            teardownStarted = true
            closeAllConnections()
        }
    }

    private suspend fun closeAllConnections(): Unit = coroutineScope {
        allConnections.forEach { launch { it.close() } }
    }
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
    options: SqliteOptions = SqliteOptions(),
): PooledSqliteWasmDatabase {
    options.beforeOpen(perConnectionRegistration(SqliteEngine.SqliteWasm))
    // Analytical page cache: SQLite's default (~2 MB) forces re-reading hot index pages through
    // OPFS on every aggregate over a large table; 32 MB keeps a 1M-row table's per-dimension
    // covering index fully cached, making repeat aggregates near-memory-speed. temp_store=MEMORY
    // keeps GROUP BY/ORDER BY scratch B-trees off the (comparatively slow) VFS as well.
    suspend fun WorkerConnection.applyPerfPragmas() {
        execute("PRAGMA cache_size=-32768", emptyList())
        execute("PRAGMA temp_store=MEMORY", emptyList())
    }

    // The caller's options go on after the perf pragmas above, so a `pragma("cache_size", …)` of
    // their own wins over Kormium's analytical default rather than being silently overwritten.
    suspend fun WorkerConnection.applyOptions() = options.suspendApplyTo(optionsScope(SqliteEngine.SqliteWasm))

    // Connections are tracked as they open so that a failure part-way through — a broken extension
    // on reader 3, say — does not leave the ones already open holding their OPFS access handles for
    // the life of the page.
    val opened = mutableListOf<WorkerConnection>()
    try {
        val writer = WorkerConnection.open(opfsPath).also { opened += it }
        writer.applyPerfPragmas()
        writer.applyOptions()
        // query_only is set ONCE here, for the reader's whole lifetime, rather than toggled on/off
        // around every read-only transaction: a reader is never picked for a write (see pickReader),
        // so there's no round trip to save by deferring it, and it also guards a suspendTransaction(
        // readOnly = true, transactional = false) call, which used to skip the old per-transaction guard.
        val readers = List(readerPoolSize) {
            WorkerConnection.open(opfsPath).also { reader ->
                opened += reader
                reader.execute("PRAGMA query_only=ON", emptyList())
                reader.applyPerfPragmas()
                reader.applyOptions()
            }
        }
        return PooledSqliteWasmDatabase(writer, readers, config)
    } catch (e: Throwable) {
        opened.forEach { runCatching { it.close() } }
        throw e
    }
}
