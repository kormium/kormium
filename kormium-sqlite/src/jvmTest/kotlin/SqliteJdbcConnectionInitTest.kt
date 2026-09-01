import io.github.kormium.SqliteConnectionScope
import io.github.kormium.SqliteEngine
import io.github.kormium.SqliteExtension
import io.github.kormium.autocommit
import io.github.kormium.createSqliteDatabase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class CountingExtension : SqliteExtension {
    override val name: String = "counting"
    override val supportedEngines: Set<SqliteEngine> = setOf(SqliteEngine.Xerial)
    val installs: AtomicInteger = AtomicInteger()
    override fun install(connection: SqliteConnectionScope) {
        installs.incrementAndGet()
        connection.exec("PRAGMA cache_size=-4096")
    }
}

/**
 * HikariCP creates connections lazily and recreates them behind our back (maxLifetime, or any
 * fatal error), so anything done to a connection at startup is lost on the replacement. That is
 * why extensions and pragmas run from a DataSource this module owns rather than from a
 * once-per-database hook: every physical connection the pool opens gets them.
 */
class SqliteJdbcConnectionInitTest {

    @Test
    fun everyPhysicalConnectionGetsTheExtension() {
        val extension = CountingExtension()
        createSqliteDatabase(poolSize = 2) { sqlite { extension(extension) } }.use { db ->
            // One query opens one physical connection.
            db.autocommit { }
            assertEquals(1, extension.installs.get(), "the first connection is initialised on demand")

            // Pin both connections at once so Hikari has to open the second one, and check it was
            // initialised too — not just the first.
            val bothPinned = CountDownLatch(2)
            val release = CountDownLatch(1)
            val threads = List(2) {
                thread {
                    db.autocommit {
                        bothPinned.countDown()
                        release.await(10, TimeUnit.SECONDS)
                    }
                }
            }
            assertTrue(bothPinned.await(10, TimeUnit.SECONDS), "both connections should have been opened")
            release.countDown()
            threads.forEach { it.join(10_000) }

            assertEquals(2, extension.installs.get(), "the connection opened later must be initialised too")

            val cacheSize = db.autocommit {
                @OptIn(io.github.kormium.DelicateKormiumApi::class)
                execute("PRAGMA cache_size", emptyMap(), emptyList()) { it.getLong(0) }
            }.single()
            assertEquals(-4096L, cacheSize)
        }
    }
}
