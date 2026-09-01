package io.github.kormium.sqlite.wasm

import io.github.kormium.SqliteEngine
import io.github.kormium.SqliteExtensionUnsupportedException
import kotlinx.coroutines.await
import kotlin.js.Promise

/** Whether the Emscripten module exports [name] — how a build's capabilities are discovered. */
private fun moduleHasExport(module: JsAny, name: String): Boolean =
    js("typeof module[name] === 'function'")

/** Fetches the side module. A browser cannot read it off disk, so it arrives over the network. */
private fun fetchArrayBuffer(url: String): Promise<JsAny> =
    js("fetch(url).then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status + ' for ' + url); return r.arrayBuffer(); })")

/** Places the side module in Emscripten's virtual filesystem, which is where dlopen looks. */
private fun writeToFs(module: JsAny, path: String, bytes: JsAny) {
    js("module.FS.writeFile(path, new Uint8Array(bytes))")
}

private fun enableLoadExtension(module: JsAny, db: JsNumber, on: Int): Int =
    js("module.ccall('sqlite3_enable_load_extension', 'number', ['number', 'number'], [db, on])")

/**
 * Loads a SQLite extension into a wa-sqlite engine at runtime.
 *
 * In the browser an extension is an Emscripten **side module**: it is fetched, written into the
 * virtual filesystem and `dlopen`ed by SQLite. That only works on an engine built with dynamic
 * linking — Kormium's default is upstream's build, compiled with `SQLITE_OMIT_LOAD_EXTENSION`, so
 * the capability is probed rather than assumed and the failure names the fix.
 *
 * SQLite derives the entry point from the file name (`vec.so` looks up `sqlite3_vec_init`), so the
 * name is preserved from [path] rather than invented here.
 */
internal suspend fun loadSideModule(
    module: JsAny,
    db: JsNumber,
    engine: SqliteEngine,
    path: String,
    entryPoint: String?,
    exec: suspend (String) -> Unit,
) {
    if (!moduleHasExport(module, "_sqlite3_enable_load_extension")) {
        throw SqliteExtensionUnsupportedException(
            extension = path,
            engine = engine,
            message = "this WASM build cannot load extensions at runtime — it comes from upstream, " +
                "compiled with SQLITE_OMIT_LOAD_EXTENSION. Pass an extension-capable engine, e.g. " +
                "@kormium/wa-sqlite-loadable, via the `engine` parameter of createSqliteWasmDatabase.",
        )
    }

    val name = path.substringAfterLast('/')
    // The name is interpolated into SQL, and it comes from a URL. Nothing legitimate here is more
    // than a file name.
    require(name.isNotEmpty() && name.all { it == '.' || it == '-' || it == '_' || it.isLetterOrDigit() }) {
        "the extension file name must be word characters, '.', '-' or '_', was '$name'"
    }
    entryPoint?.let {
        require(it.isNotEmpty() && it.all { c -> c == '_' || c.isLetterOrDigit() }) {
            "the entry point must be word characters, was '$it'"
        }
    }

    writeToFs(module, "/$name", fetchArrayBuffer(path).await<JsAny>())

    // Armed only for this call: leaving load_extension() enabled would let any SQL in the page
    // load code. There is no db_config equivalent reachable from JS here, so this is the C API's
    // coarser switch, turned straight back off.
    enableLoadExtension(module, db, 1)
    try {
        val call = if (entryPoint == null) {
            "select load_extension('/$name')"
        } else {
            "select load_extension('/$name', '$entryPoint')"
        }
        exec(call)
    } finally {
        enableLoadExtension(module, db, 0)
    }
}
