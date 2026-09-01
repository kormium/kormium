package io.github.kormium.sqlite.js

import kotlin.js.Promise

/**
 * Which WASM SQLite build this engine runs on — the Kotlin/JS sibling of
 * `kormium-sqlite-wasm`'s `SqliteWasmEngine`.
 *
 * In the browser a SQLite extension is a different build, because nothing can be linked into a WASM
 * module after the fact. Taking the build as a parameter moves that choice to the caller, so anyone
 * can publish an extension-capable engine and point Kormium at it. See ADR 0013, decision 7.
 */
public fun interface SqliteJsEngine {

    /** Instantiates the Emscripten module; [config] is the caller's `moduleConfig`, passed through. */
    public fun instantiate(config: Any?): Promise<Any>

    public companion object {
        /** wa-sqlite's async build, the one Kormium depends on and the default. */
        public val Default: SqliteJsEngine = SqliteJsEngine { config ->
            if (config == null) SQLiteESMFactory() else SQLiteESMFactory(config)
        }
    }
}
