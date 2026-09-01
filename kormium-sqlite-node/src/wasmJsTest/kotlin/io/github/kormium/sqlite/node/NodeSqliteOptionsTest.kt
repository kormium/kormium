@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

package io.github.kormium.sqlite.node

import io.github.kormium.SqliteConnectionScope
import io.github.kormium.SqliteEngine
import io.github.kormium.SqliteExtension
import io.github.kormium.SqliteExtensionUnsupportedException
import io.github.kormium.SqliteOptions
import io.github.kormium.sqliteOptions
import io.github.kormium.suspendAutocommit
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class ProbingExtension : SqliteExtension {
    override val name: String = "probing"
    override val supportedEngines: Set<SqliteEngine> = setOf(SqliteEngine.BetterSqlite3)
    var probed: String? = null
    override fun install(connection: SqliteConnectionScope) {
        probed = connection.queryScalar("select sqlite_version()")
    }
}

private class ServerJvmOnlyExtension : SqliteExtension {
    override val name: String = "jvm-only"
    override val supportedEngines: Set<SqliteEngine> = setOf(SqliteEngine.Xerial)
}

/**
 * `SqliteOptions` on the Node engine. better-sqlite3 is synchronous, so extensions install through
 * the blocking half of the SPI here — the same one the JVM and native drivers use.
 */
class NodeSqliteOptionsTest {

    @Test
    fun pragmaIsAppliedToTheConnection() = runTest {
        val db = createNodeSqliteDatabase(options = sqliteOptions { pragma("cache_size", "-8192") })
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
    fun extensionInstallsThroughTheBlockingHalfOfTheSpi() = runTest {
        val extension = ProbingExtension()
        val db = createNodeSqliteDatabase(options = SqliteOptions(extension))
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
            createNodeSqliteDatabase(options = SqliteOptions(ServerJvmOnlyExtension()))
        } catch (e: SqliteExtensionUnsupportedException) {
            failure = e
        }
        assertEquals("jvm-only", failure?.extension)
        assertEquals(SqliteEngine.BetterSqlite3, failure?.engine)
    }
}
