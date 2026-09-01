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
