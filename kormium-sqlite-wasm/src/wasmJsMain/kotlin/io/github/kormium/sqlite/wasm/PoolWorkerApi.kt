package io.github.kormium.sqlite.wasm

/** One `postMessage` event from a pool Worker. */
internal external interface PoolWorkerEvent : JsAny {
    val data: JsAny
}

/** One `Worker.onerror` event: the Worker's script itself failed (e.g. a load/parse error). */
internal external interface PoolWorkerErrorEvent : JsAny {
    val message: String?
}

/** A spawned pool-worker (the `sqlite-wasm-kt-worker` bundle) — dedicated Worker JS shape. */
internal external interface PoolWorker : JsAny {
    var onmessage: ((PoolWorkerEvent) -> Unit)?
    var onerror: ((PoolWorkerErrorEvent) -> Unit)?
    fun postMessage(message: JsAny)

    /** Ends the Worker immediately. Called by [WorkerConnection.close] only after a graceful
     *  `close` RPC round trip has released the connection's OPFS access handle (or timed out). */
    fun terminate()
}

/**
 * Spawns the standalone worker bundle via its npm-resolved specifier, so webpack's `new
 * Worker(new URL(specifier, import.meta.url))` bundling picks it up as its own chunk — verified
 * empirically to work with zero consumer-side webpack config. `{ type: 'module' }` because the
 * package ships raw ESM, not a pre-bundled UMD file: a pre-bundled file is one opaque CommonJS
 * module to the consumer's own webpack, which then never re-discovers/re-emits
 * `@sqlite.org/sqlite-wasm`'s own `sqlite3.wasm` asset reference *inside* that opaque blob
 * (confirmed empirically — the asset silently never makes it into the final dist directory). Raw
 * ESM source doesn't have this problem: the consumer's webpack traces it like any other module.
 */
internal fun spawnPoolWorker(): PoolWorker = js(
    "new Worker(new URL('@kormium/sqlite-wasm-worker/dist/sqlite-wasm-kt-workspace-sqlite-wasm-kt-worker.mjs', import.meta.url), { type: 'module' })",
)

/** One reply from the pool worker (mirrors `sqlite-wasm-kt-worker`'s `Protocol.kt` response shape). */
internal external interface PoolWorkerResponse : JsAny {
    val id: Int
    val ok: Boolean
    val error: String?
    val affected: JsAny?
    val columns: JsArray<JsAny?>?
    val rows: JsArray<JsAny?>?
}

internal fun openRequest(id: Int, opfsPath: String?): JsAny =
    js("({ id: id, kind: 'open', opfsPath: opfsPath })")

internal fun executeRequest(id: Int, sql: String, params: JsArray<JsAny?>): JsAny =
    js("({ id: id, kind: 'execute', sql: sql, params: params })")

internal fun queryRequest(id: Int, sql: String, params: JsArray<JsAny?>): JsAny =
    js("({ id: id, kind: 'query', sql: sql, params: params })")

internal fun closeRequest(id: Int): JsAny = js("({ id: id, kind: 'close' })")

// ---- small local JS-array helpers (mirrors of sqlite-wasm-kt's internal ones; that module's are
// not visible here — different Kotlin module — so this is a deliberate, tiny, private duplicate) ----

internal fun jsArrayLength(value: JsAny): Int = js("value.length")
internal fun jsArrayGet(value: JsAny, index: Int): JsAny? = js("value[index]")
internal fun emptyJsArray(): JsArray<JsAny?> = js("[]")
