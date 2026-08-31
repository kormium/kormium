@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

import io.github.kormium.SqliteConnectionScope
import io.github.kormium.SqliteEngine
import io.github.kormium.SqliteExtension
import io.github.kormium.SqliteExtensionUnsupportedException
import io.github.kormium.SqliteRegistrationScope
import io.github.kormium.autocommit
import io.github.kormium.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Records what the driver did to it, so the plumbing can be asserted without a real extension. */
private class RecordingExtension(private val pragma: String? = null) : SqliteExtension {
    override val name: String = "recording"
    override val supportedEngines: Set<SqliteEngine> = SqliteEngine.entries.toSet()
    var beforeOpenCalls: Int = 0
    var installs: Int = 0
    var seenEngine: SqliteEngine? = null

    override fun beforeOpen(registration: SqliteRegistrationScope) {
        beforeOpenCalls++
        seenEngine = registration.engine
    }

    override fun install(connection: SqliteConnectionScope) {
        installs++
        seenEngine = connection.engine
        if (pragma != null) connection.exec(pragma)
    }
}

/** Declares support for an engine no platform driver here reports, so every driver must reject it. */
private class ForeignEngineExtension : SqliteExtension {
    override val name: String = "foreign"
    override val supportedEngines: Set<SqliteEngine> = setOf(SqliteEngine.WaSqlite)
    override fun install(connection: SqliteConnectionScope): Unit = error("must never be installed")
}

private class FailingExtension : SqliteExtension {
    override val name: String = "failing"
    override val supportedEngines: Set<SqliteEngine> = SqliteEngine.entries.toSet()
    override fun install(connection: SqliteConnectionScope) = error("boom")
}

/**
 * The `sqlite { }` options: extensions and pragmas reach every connection the driver opens. This
 * runs on every platform driver (JVM, native, Android) — the mechanisms differ, the contract does
 * not.
 */
class SqliteExtensionOptionsTest {

    @Test
    fun extensionSeesEveryConnectionAndTheRightEngine() {
        val extension = RecordingExtension(pragma = "PRAGMA cache_size=-2048")
        createSqliteDatabase { sqlite { extension(extension) } }.use { db ->
            assertEquals(1, extension.beforeOpenCalls, "beforeOpen runs once per driver")
            assertTrue(extension.installs >= 1, "install must run on the pooled connection")
            assertTrue(extension.seenEngine != null, "the scope must report its engine")

            val cacheSize = db.autocommit {
                execute("PRAGMA cache_size", emptyMap(), emptyList()) { it.getLong(0) }
            }.single()
            assertEquals(-2048L, cacheSize, "the extension's pragma must survive on the pooled connection")
        }
    }

    @Test
    fun declaredPragmaWinsOverKormiumDefault() {
        // Kormium turns foreign_keys ON by default; an explicit pragma must beat it, the same way
        // one written into a `file:` path does.
        createSqliteDatabase { sqlite { pragma("foreign_keys", "off") } }.use { db ->
            val foreignKeys = db.autocommit {
                execute("PRAGMA foreign_keys", emptyMap(), emptyList()) { it.getLong(0) }
            }.single()
            assertEquals(0L, foreignKeys)
        }
    }

    @Test
    fun defaultsStillApplyWithoutOptions() {
        createSqliteDatabase().use { db ->
            val foreignKeys = db.autocommit {
                execute("PRAGMA foreign_keys", emptyMap(), emptyList()) { it.getLong(0) }
            }.single()
            assertEquals(1L, foreignKeys)
        }
    }

    @Test
    fun anExtensionThatCannotInstallFailsTheOpen() {
        // The point of installing while the database is opening: a broken extension is reported
        // here, not at the first query that would have used it.
        assertFails { createSqliteDatabase { sqlite { extension(FailingExtension()) } } }
    }

    @Test
    fun anExtensionBuiltForOtherEnginesIsRejectedByName() {
        // Without the up-front capability check this would install nothing and only show up later
        // as "no such module" on the first query that needed the extension.
        val failure = assertFailsWith<SqliteExtensionUnsupportedException> {
            createSqliteDatabase { sqlite { extension(ForeignEngineExtension()) } }
        }
        assertEquals("foreign", failure.extension)
        assertTrue(failure.message!!.contains("does not support"), failure.message!!)
    }

    @Test
    fun pragmaRejectsValuesItWouldHaveToInterpolate() {
        assertFails { createSqliteDatabase { sqlite { pragma("cache_size", "1; drop table t") } } }
        assertFails { createSqliteDatabase { sqlite { pragma("bad name", "1") } } }
    }
}
