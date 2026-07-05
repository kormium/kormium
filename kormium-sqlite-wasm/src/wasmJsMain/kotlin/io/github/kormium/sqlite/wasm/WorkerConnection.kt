package io.github.kormium.sqlite.wasm

import io.github.kormium.sqlException
import io.github.kormium.sqlitewasm.decodeSqliteWasmParams
import io.github.kormium.sqlitewasm.encodeSqliteWasmParams
import kotlinx.coroutines.CompletableDeferred

/** Column names + rows (each already decoded to Kotlin values) from one [WorkerConnection.query]. */
internal class WorkerQueryResult(val columnNames: List<String>, val rows: List<List<Any?>>)

/** The pool worker posts this (`id == READY_ID`) the moment its message listener is registered, so
 *  [WorkerConnection.open] can wait for it instead of racing the very first real request against
 *  the browser's worker-startup/message-queueing timing. */
private const val READY_ID = -1

/**
 * One pooled connection: a dedicated Worker running `sqlite-wasm-kt-worker`'s RPC endpoint over its
 * own [io.github.kormium.sqlitewasm.SqliteWasmConnection]. Requests are sent one at a time — a call
 * awaits its reply before the next is sent, matching the worker's single in-flight assumption — so
 * real concurrency comes from multiple [WorkerConnection]s (the reader pool), not pipelining within
 * one.
 */
internal class WorkerConnection private constructor(private val worker: PoolWorker) {
    private var nextId = 0
    private val pending = HashMap<Int, CompletableDeferred<PoolWorkerResponse>>()
    private val ready = CompletableDeferred<Unit>()

    init {
        worker.onmessage = { event ->
            val response = event.data as PoolWorkerResponse
            if (response.id == READY_ID) ready.complete(Unit) else pending.remove(response.id)?.complete(response)
        }
        worker.onerror = { event ->
            val failure = sqlException(event.message ?: "pool worker crashed", sqlState = null)
            ready.completeExceptionally(failure)
            pending.values.forEach { it.completeExceptionally(failure) }
            pending.clear()
        }
    }

    private suspend fun roundTrip(build: (Int) -> JsAny): PoolWorkerResponse {
        val id = nextId++
        val deferred = CompletableDeferred<PoolWorkerResponse>()
        pending[id] = deferred
        worker.postMessage(build(id))
        val response = deferred.await()
        if (!response.ok) throw sqlException(response.error ?: "pool worker error", sqlState = null)
        return response
    }

    suspend fun execute(sql: String, params: List<Any?>): Long {
        val response = roundTrip { id -> executeRequest(id, sql, encodeSqliteWasmParams(params)) }
        return (response.affected as JsNumber).toDouble().toLong()
    }

    suspend fun query(sql: String, params: List<Any?>): WorkerQueryResult {
        val response = roundTrip { id -> queryRequest(id, sql, encodeSqliteWasmParams(params)) }
        val columnNames = decodeSqliteWasmParams(response.columns ?: emptyJsArray()).map { it as? String ?: "" }
        val rawRows = response.rows ?: emptyJsArray()
        val rows = List(jsArrayLength(rawRows)) { i ->
            val row = jsArrayGet(rawRows, i) ?: return@List emptyList()
            decodeSqliteWasmParams(row as JsArray<JsAny?>)
        }
        return WorkerQueryResult(columnNames, rows)
    }

    /** Ends the underlying Worker immediately; no graceful RPC round trip (see [PoolWorker.terminate]). */
    fun terminate() {
        worker.terminate()
    }

    companion object {
        /** Spawns a Worker, waits for its ready signal, then opens a connection on it
         *  (`opfsPath == null` means in-memory). */
        suspend fun open(opfsPath: String?): WorkerConnection {
            val connection = WorkerConnection(spawnPoolWorker())
            connection.ready.await()
            connection.roundTrip { id -> openRequest(id, opfsPath) }
            return connection
        }
    }
}
