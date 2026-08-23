@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

import io.github.kormium.SqliteDriver
import io.github.kormium.autocommit
import io.github.kormium.createSqliteDatabase
import io.github.kormium.transaction
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `:memory:` isolation on JVM/native (https://github.com/kormium/kormium/issues/131). Both
 * drivers used to open the same `file::memory:?cache=shared` URI, so unrelated
 * [createSqliteDatabase] calls in one process silently shared one physical database — a "fresh"
 * test database showing a neighbour's rows. Each call now gets its own name, which has to hold
 * three ways at once: separate drivers are isolated, one driver's pool still shares a database
 * across its connections, and an explicit URI can still opt back into sharing.
 *
 * Isolation is asserted positively — the same table exists in both databases and only one of
 * them holds the row — rather than by expecting a failure in the second database, which would
 * also "pass" if that driver were broken in some entirely different way.
 */
class SqliteMemoryIsolationTest {

    private fun createTable(db: SqliteDriver, name: String) = db.autocommit {
        executeUpdate("CREATE TABLE $name(x INTEGER)", params = emptyMap(), invalidates = emptyList())
    }

    private fun insertRow(db: SqliteDriver, name: String) = db.autocommit {
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
            createTable(b, "iso_default")
            insertRow(a, "iso_default")
            assertEquals(1, countIn(a, "iso_default"))
            assertEquals(0, countIn(b, "iso_default"), "b must not see a's row")
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
            createTable(b, "iso_pooled")
            insertRow(a, "iso_pooled")
            assertEquals(1, countIn(a, "iso_pooled"))
            assertEquals(0, countIn(b, "iso_pooled"), "b must not see a's row")
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
            insertRow(db, "iso_pool_shared")
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
            insertRow(a, "iso_shared_uri")
            assertEquals(1, countIn(b, "iso_shared_uri"))
        } finally {
            a.close()
            b.close()
        }
    }

    @Test
    fun pragmasInThePathWinOverKormiumsDefaults() {
        // busy_timeout is Kormium's to set (5000) unless the caller says otherwise in the path.
        // Both backends have to honour it, by different routes: sqlite-jdbc parses the parameter
        // on the JVM, while native reads it back out of the URI and issues the PRAGMA itself.
        val db = createSqliteDatabase("file:kormium-pragma-test?mode=memory&cache=shared&busy_timeout=1234")
        try {
            val timeout = db.autocommit {
                execute("PRAGMA busy_timeout", params = emptyMap(), invalidates = emptyList()) { it.getInt(0) }
            }.single()
            assertEquals(1234, timeout)
        } finally {
            db.close()
        }
    }

    @Test
    fun kormiumsDefaultPragmasStillApply() {
        val db = createSqliteDatabase()
        try {
            val (foreignKeys, timeout) = db.autocommit {
                val fk = execute("PRAGMA foreign_keys", params = emptyMap(), invalidates = emptyList()) { it.getInt(0) }
                    .single()
                val bt = execute("PRAGMA busy_timeout", params = emptyMap(), invalidates = emptyList()) { it.getInt(0) }
                    .single()
                fk to bt
            }
            assertEquals(1, foreignKeys)
            assertEquals(5000, timeout)
        } finally {
            db.close()
        }
    }
}
