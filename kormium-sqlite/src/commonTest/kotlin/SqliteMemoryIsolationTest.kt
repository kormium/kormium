@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

import io.github.kormium.QueryException
import io.github.kormium.SqliteDriver
import io.github.kormium.autocommit
import io.github.kormium.createSqliteDatabase
import io.github.kormium.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `:memory:` isolation on JVM/native (https://github.com/kormium/kormium/issues/131). Both
 * drivers used to open the same `file::memory:?cache=shared` URI, so unrelated
 * [createSqliteDatabase] calls in one process silently shared one physical database — a "fresh"
 * test database showing a neighbour's rows. Each call now gets its own name, which has to hold
 * three ways at once: separate drivers are isolated, one driver's pool still shares a database
 * across its connections, and an explicit URI can still opt back into sharing.
 */
class SqliteMemoryIsolationTest {

    private fun createTable(db: SqliteDriver, name: String) = db.autocommit {
        executeUpdate("CREATE TABLE $name(x INTEGER)", params = emptyMap(), invalidates = emptyList())
        executeUpdate("INSERT INTO $name(x) VALUES (1)", params = emptyMap(), invalidates = emptyList())
    }

    private fun countIn(db: SqliteDriver, name: String): Int =
        db.autocommit {
            execute("SELECT count(*) FROM $name", params = emptyMap(), invalidates = emptyList()) { it.getInt(0) ?: 0 }
        }.single()

    @Test
    fun separateDriversDoNotShareState() {
        val a = createSqliteDatabase()
        val b = createSqliteDatabase()
        try {
            createTable(a, "iso_default")
            // b never saw a's CREATE TABLE, so the table simply does not exist there.
            assertFailsWith<QueryException> { countIn(b, "iso_default") }
        } finally {
            a.close()
            b.close()
        }
    }

    @Test
    fun separatePooledDriversDoNotShareState() {
        val a = createSqliteDatabase(poolSize = 2)
        val b = createSqliteDatabase(poolSize = 2)
        try {
            createTable(a, "iso_pooled")
            assertFailsWith<QueryException> { countIn(b, "iso_pooled") }
        } finally {
            a.close()
            b.close()
        }
    }

    @Test
    fun oneDriversPoolSharesOneDatabase() {
        val db = createSqliteDatabase(poolSize = 2)
        try {
            createTable(db, "iso_pool_shared")
            // The outer transaction pins one connection for its whole body, so the nested
            // autocommit is forced onto the pool's *other* connection — which must see the
            // database the first one created (a read, so no shared-cache table lock is held).
            val seen = db.transaction {
                execute("SELECT 1", params = emptyMap(), invalidates = emptyList()) { it.getInt(0) }
                countIn(db, "iso_pool_shared")
            }
            assertEquals(1, seen)
        } finally {
            db.close()
        }
    }

    @Test
    fun explicitSharedUriOptsBackIntoSharing() {
        // The documented escape hatch for callers who *want* one in-memory database behind
        // several drivers. It lives as long as one of them is open.
        val uri = "file:kormium-isolation-test?mode=memory&cache=shared"
        val a = createSqliteDatabase(uri)
        val b = createSqliteDatabase(uri)
        try {
            createTable(a, "iso_shared_uri")
            assertEquals(1, countIn(b, "iso_shared_uri"))
        } finally {
            a.close()
            b.close()
        }
    }
}
