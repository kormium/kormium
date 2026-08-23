@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

import io.github.kormium.SqliteDriver
import io.github.kormium.autocommit
import io.github.kormium.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An in-memory SQLite database exists only while some connection to it is open, and HikariCP
 * recycles pooled connections on its own schedule — `maxLifetime`, 30 minutes by default, plus
 * eviction after a fatal error. At the default `poolSize = 1` that leaves an instant with no
 * connection at all, which drops the database: a long-running process would silently find its
 * cache empty half an hour in. The JVM driver therefore holds one unpooled connection open for
 * its whole life.
 *
 * Waiting out `maxLifetime` is not a test, so this reaches into HikariCP and asks for the same
 * eviction directly. Reflection keeps the pool internals out of Kormium's API — the alternative
 * would be exposing a knob that exists only for this test.
 */
class SqliteInMemoryKeepAliveTest {

    private fun softEvictPooledConnections(db: SqliteDriver) {
        val dataSource = db.javaClass.superclass.getDeclaredField("ds")
            .apply { isAccessible = true }
            .get(db)
        val poolBean = dataSource.javaClass.getMethod("getHikariPoolMXBean").invoke(dataSource)
        Class.forName("com.zaxxer.hikari.HikariPoolMXBean")
            .getMethod("softEvictConnections")
            .invoke(poolBean)
    }

    private fun countIn(db: SqliteDriver, name: String): Int =
        db.autocommit {
            execute("SELECT count(*) FROM $name", params = emptyMap(), invalidates = emptyList()) { it.getInt(0) ?: 0 }
        }.single()

    @Test
    fun inMemoryDatabaseSurvivesConnectionRecycling() {
        val db = createSqliteDatabase()
        try {
            db.autocommit {
                executeUpdate("CREATE TABLE keep_alive(x INTEGER)", params = emptyMap(), invalidates = emptyList())
                executeUpdate("INSERT INTO keep_alive(x) VALUES (1)", params = emptyMap(), invalidates = emptyList())
            }
            softEvictPooledConnections(db)
            assertEquals(1, countIn(db, "keep_alive"), "the database must outlive the pooled connection")
        } finally {
            db.close()
        }
    }

    @Test
    fun explicitlySharedInMemoryUriAlsoSurvives() {
        val uri = "file:kormium-keep-alive-test?mode=memory&cache=shared"
        val db = createSqliteDatabase(uri)
        try {
            db.autocommit {
                executeUpdate("CREATE TABLE keep_alive_uri(x INTEGER)", params = emptyMap(), invalidates = emptyList())
                executeUpdate(
                    "INSERT INTO keep_alive_uri(x) VALUES (1)",
                    params = emptyMap(),
                    invalidates = emptyList(),
                )
            }
            softEvictPooledConnections(db)
            assertEquals(1, countIn(db, "keep_alive_uri"))
        } finally {
            db.close()
        }
    }
}
