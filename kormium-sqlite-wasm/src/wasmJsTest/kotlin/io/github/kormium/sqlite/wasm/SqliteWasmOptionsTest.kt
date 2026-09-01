@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

package io.github.kormium.sqlite.wasm

import io.github.kormium.SqliteEngine
import io.github.kormium.SqliteExtension
import io.github.kormium.SqliteExtensionUnsupportedException
import io.github.kormium.SqliteOptions
import io.github.kormium.sqliteOptions
import io.github.kormium.SuspendSqliteConnectionScope
import io.github.kormium.suspendAutocommit
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// wa-sqlite's async build fetches its .wasm, which Node's fetch rejects for file://; hand the bytes
// to the factory instead (same trick as SqliteWasmIntegrationTest).
private fun wasmConfig(): JsAny =
    js("(function(){ var fs = require('node:fs'); var p = require.resolve('wa-sqlite/dist/wa-sqlite-async.wasm'); return { wasmBinary: fs.readFileSync(p) }; })()")

private class ServerOnlyExtension : SqliteExtension {
    override val name: String = "server-only"
    override val supportedEngines: Set<SqliteEngine> = setOf(SqliteEngine.Xerial, SqliteEngine.Native)
}

/** Asks the engine to load a side module — which only an extension-capable build can do. */
private class LoadingExtension : SqliteExtension {
    override val name: String = "loading"
    override val supportedEngines: Set<SqliteEngine> = setOf(SqliteEngine.WaSqlite)
    override suspend fun suspendInstall(connection: SuspendSqliteConnectionScope) {
        connection.loadLibrary("https://example.invalid/vec.so")
    }
}

private class BrowserExtension : SqliteExtension {
    override val name: String = "browser-ok"
    override val supportedEngines: Set<SqliteEngine> = setOf(SqliteEngine.WaSqlite)
    var probed: String? = null
    override suspend fun suspendInstall(connection: SuspendSqliteConnectionScope) {
        probed = connection.queryScalar("select sqlite_version()")
    }
}

/**
 * `SqliteOptions` on the browser engine. Extensions here go through the **suspend** half of the SPI:
 * wa-sqlite's VFS is asynchronous, so there is no blocking scope to install against.
 */
class SqliteWasmOptionsTest {

    @Test
    fun pragmaIsAppliedToTheConnection() = runTest {
        val db = createSqliteWasmDatabase(
            moduleConfig = wasmConfig(),
            options = sqliteOptions { pragma("cache_size", "-8192") },
        )
        try {
            val cacheSize = db.suspendAutocommit {
                execute("PRAGMA cache_size", emptyMap(), emptyList()) { it.getLong(0) }
            }.single()
            assertEquals(-8192L, cacheSize)
        } finally {
            db.close()
        }
    }

    @Test
    fun extensionRunsThroughTheSuspendHalfOfTheSpi() = runTest {
        val extension = BrowserExtension()
        val db = createSqliteWasmDatabase(
            moduleConfig = wasmConfig(),
            options = SqliteOptions(extension),
        )
        try {
            assertTrue(extension.probed!!.startsWith("3."), "probed ${extension.probed}")
        } finally {
            db.close()
        }
    }

    /**
     * The default engine is upstream's build, compiled with `SQLITE_OMIT_LOAD_EXTENSION`, so it
     * cannot load anything and must say so by name. The successful path needs the loadable build
     * and a real fetch, which Node cannot do for a local file — it is covered end to end by
     * kormium/sqlite-wasm-engines' own smoke tests instead.
     */
    @Test
    fun loadingIntoTheDefaultEngineFailsWithTheFix() = runTest {
        var failure: SqliteExtensionUnsupportedException? = null
        try {
            createSqliteWasmDatabase(moduleConfig = wasmConfig(), options = SqliteOptions(LoadingExtension()))
        } catch (e: SqliteExtensionUnsupportedException) {
            failure = e
        }
        assertEquals(SqliteEngine.WaSqlite, failure?.engine)
        assertTrue(
            failure!!.message!!.contains("wa-sqlite-loadable"),
            "the message should name the engine that can do it, was: ${failure.message}",
        )
    }

    @Test
    fun anExtensionBuiltForOtherEnginesIsRejectedByName() = runTest {
        var failure: SqliteExtensionUnsupportedException? = null
        try {
            createSqliteWasmDatabase(moduleConfig = wasmConfig(), options = SqliteOptions(ServerOnlyExtension()))
        } catch (e: SqliteExtensionUnsupportedException) {
            failure = e
        }
        assertEquals("server-only", failure?.extension)
        assertEquals(SqliteEngine.WaSqlite, failure?.engine)
    }
}
