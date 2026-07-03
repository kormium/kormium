@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

package io.github.kormium.r2dbc

import io.github.kormium.TransactionIsolation
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.suspendAutocommit
import io.github.kormium.suspendTransaction
import kotlinx.coroutines.runBlocking
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Covers the portable transaction-options API on the async r2dbc backend, where isolation and
 * read-only are carried through an r2dbc `TransactionDefinition` (the novel path versus the
 * SQL/JDBC backends).
 */
class R2dbcTransactionOptionsTest {

    private val dockerAvailable = DockerClientFactory.instance().isDockerAvailable
    private var container: PostgreSQLContainer<*>? = null
    private var db: SuspendDatabase<R2Catalog>? = null

    @BeforeTest
    fun setUp() {
        if (!dockerAvailable) return
        val pg = PostgreSQLContainer("postgres:16-alpine")
        pg.start()
        container = pg
        db = createR2dbcDatabase(
            host = pg.host,
            port = pg.firstMappedPort,
            database = pg.databaseName,
            user = pg.username,
            password = pg.password,
            poolSize = 4,
        )
    }

    @AfterTest
    fun tearDown() {
        db?.close()
        container?.stop()
    }

    @Test
    fun isolationAndReadOnlyAreCarriedThroughTheTransactionDefinition() {
        if (!dockerAvailable) return
        val database = db!!
        runBlocking {
            val level = database.suspendTransaction(isolation = TransactionIsolation.Serializable) {
                execute("SHOW transaction_isolation", params = emptyMap(), invalidates = emptyList()) { it.getString(0) }.first()
            }
            assertEquals("serializable", level)

            val readOnly = database.suspendTransaction(readOnly = true) {
                execute("SHOW transaction_read_only", params = emptyMap(), invalidates = emptyList()) { it.getString(0) }.first()
            }
            assertEquals("on", readOnly)

            database.suspendAutocommit {
                executeUpdate("""CREATE TABLE IF NOT EXISTS r2_ro_widgets ("id" int PRIMARY KEY)""", params = emptyMap(), invalidates = emptyList())
            }
            assertFailsWith<Throwable> {
                database.suspendTransaction(readOnly = true) {
                    executeUpdate("INSERT INTO r2_ro_widgets (\"id\") VALUES (1)", params = emptyMap(), invalidates = emptyList())
                }
            }
        }
    }
}
