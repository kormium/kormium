package io.github.kormium.sqlite.js

import io.github.kormium.SqliteEngine
import io.github.kormium.SqliteExtensionUnsupportedException
import kotlinx.coroutines.await
import kotlin.js.Promise

/** Whether the Emscripten module exports [name] — how a build's capabilities are discovered. */
private fun moduleHasExport(module: Any, name: String): Boolean =
    js("typeof module[name] === 'function'") as Boolean

/** Fetches the side module. A browser cannot read it off disk, so it arrives over the network. */
private fun fetchArrayBuffer(url: String): Promise<Any> =
    js("fetch(url).then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status + ' for ' + url); return r.arrayBuffer(); })") as Promise<Any>

/** Places the side module in Emscripten's virtual filesystem, which is where dlopen looks. */
private fun writeToFs(module: Any, path: String, bytes: Any) {
    js("module.FS.writeFile(path, new Uint8Array(bytes))")
}

private fun enableLoadExtension(module: Any, db: Int, on: Int) {
    js("module.ccall('sqlite3_enable_load_extension', 'number', ['number', 'number'], [db, on])")
}

/**
 * Loads a SQLite extension into the Kotlin/JS wa-sqlite engine — the sibling of
 * `kormium-sqlite-wasm`'s loader, and the same mechanism: an extension in the browser is an
 * Emscripten side module, fetched, written into the virtual filesystem and `dlopen`ed.
 *
 * Only an engine built with dynamic linking can do it; Kormium's default is upstream's build,
 * compiled with `SQLITE_OMIT_LOAD_EXTENSION`, so the capability is probed and the failure names the
 * fix rather than surfacing as a missing symbol.
 */
internal suspend fun loadSideModule(
    module: Any,
    db: Int,
    path: String,
    entryPoint: String?,
    exec: suspend (String) -> Unit,
) {
    if (!moduleHasExport(module, "_sqlite3_enable_load_extension")) {
        throw SqliteExtensionUnsupportedException(
            extension = path,
            engine = SqliteEngine.WaSqlite,
            message = "this WASM build cannot load extensions at runtime — it comes from upstream, " +
                "compiled with SQLITE_OMIT_LOAD_EXTENSION. Pass an extension-capable engine, e.g. " +
                "@kormium/wa-sqlite-loadable, via the `engine` parameter of createSqliteJsDatabase.",
        )
    }

    // SQLite derives the entry point from the file name (vec.so -> sqlite3_vec_init), so the name
    // is preserved from the URL. It is interpolated into SQL, hence the restriction.
    val name = path.substringAfterLast('/')
    require(name.isNotEmpty() && name.all { it == '.' || it == '-' || it == '_' || it.isLetterOrDigit() }) {
        "the extension file name must be word characters, '.', '-' or '_', was '$name'"
    }
    entryPoint?.let {
        require(it.isNotEmpty() && it.all { c -> c == '_' || c.isLetterOrDigit() }) {
            "the entry point must be word characters, was '$it'"
        }
    }

    writeToFs(module, "/$name", fetchArrayBuffer(path).await())

    // Armed only for this call: leaving load_extension() enabled would let any SQL in the page
    // load code.
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
