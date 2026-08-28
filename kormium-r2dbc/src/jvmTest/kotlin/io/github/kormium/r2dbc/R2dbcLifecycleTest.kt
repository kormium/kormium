@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

package io.github.kormium.r2dbc

import io.github.kormium.DatabaseClosedException
import io.github.kormium.suspendAutocommit
import io.github.kormium.suspendTransaction
import kotlinx.coroutines.runBlocking
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Lifecycle contract for the async r2dbc backend: idempotent close, `isClosed` reflects state,
 * and use-after-close throws the uniform [DatabaseClosedException]. Skips when Docker is absent.
 */
class R2dbcLifecycleTest {

    private val dockerAvailable = DockerClientFactory.instance().isDockerAvailable

    private fun withDb(block: (db: io.github.kormium.database.SuspendDatabase<R2Catalog>) -> Unit) {
        val pg = PostgreSQLContainer("postgres:18-alpine")
        pg.start()
        try {
            val db = createR2dbcDatabase(
                host = pg.host, port = pg.firstMappedPort, database = pg.databaseName,
                user = pg.username, password = pg.password, poolSize = 2,
            )
            block(db)
        } finally {
            pg.stop()
        }
    }

    @Test
    fun isClosedAndDoubleClose() {
        if (!dockerAvailable) return
        withDb { db ->
            assertFalse(db.isClosed)
            db.close()
            assertTrue(db.isClosed)
            db.close() // idempotent
            assertTrue(db.isClosed)
        }
    }

    @Test
    fun useAfterCloseThrowsDatabaseClosed() {
        if (!dockerAvailable) return
        withDb { db ->
            db.close()
            runBlocking {
                assertFailsWith<DatabaseClosedException> { db.suspendAutocommit { executeUpdate("SELECT 1", params = emptyMap(), invalidates = emptyList()) } }
                assertFailsWith<DatabaseClosedException> { db.suspendTransaction { executeUpdate("SELECT 1", params = emptyMap(), invalidates = emptyList()) } }
            }
        }
    }
}
