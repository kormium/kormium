package io.github.kormium.sqlite.wasm

import kotlin.js.Promise

/**
 * Which WASM SQLite build an engine runs on.
 *
 * Which `.wasm` gets loaded used to be fixed at compile time by a `@JsModule` inside Kormium
 * (`WaSqliteFactory.kt`), which meant a caller could not swap it — and in the browser a SQLite
 * extension *is* a different build, because nothing can be linked into a WASM module after the
 * fact. Taking the build as a parameter moves that choice to the caller, so anyone can publish an
 * extension-capable engine and point Kormium at it, and Kormium curates no list of them.
 * See ADR 0013, decision 7.
 *
 * ```kotlin
 * @JsModule("@example/sqlite-wasm-vec/dist/sqlite.mjs")
 * external fun VecFactory(config: JsAny? = definedExternally): Promise<JsAny>
 *
 * val db = createSqliteWasmDatabase(engine = SqliteWasmEngine { VecFactory(it) })
 * ```
 */
public fun interface SqliteWasmEngine {

    /**
     * Instantiates the Emscripten module. [config] is the caller's `moduleConfig`, passed through
     * untouched (e.g. `{ wasmBinary: … }` under Node, where fetch cannot read `file://`).
     */
    public fun instantiate(config: JsAny?): Promise<JsAny>

    public companion object {
        /** wa-sqlite's async build, the one Kormium depends on and the default. */
        public val Default: SqliteWasmEngine = SqliteWasmEngine { config ->
            if (config == null) SQLiteESMFactory() else SQLiteESMFactory(config)
        }
    }
}
