@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

package io.github.kormium.r2dbc

import io.github.kormium.Query
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.eq
import io.github.kormium.suspendAutocommit
import io.github.kormium.suspendTransaction
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * Cancellation semantics of the truly-async r2dbc backend. `R2dbcDatabase.useConnection` rolls
 * back and closes the connection under `NonCancellable`, so a cancelled scope must still roll
 * back its work and return its connection. The pool holds a single connection, so the follow-up
 * query proves the connection was released (it would block — caught by withTimeout — if leaked).
 * Skips when Docker is unavailable. See docs/transactions-and-migrations.md.
 */
class R2dbcCancellationTest {

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
            host = pg.host, port = pg.firstMappedPort, database = pg.databaseName,
            user = pg.username, password = pg.password, poolSize = 1,
        )
        runBlocking {
            db!!.suspendTransaction {
                Widgets.execSql(
                    """CREATE TABLE IF NOT EXISTS "widgets" ("id" uuid NOT NULL, "name" text NOT NULL, "qty" integer NOT NULL, PRIMARY KEY ("id"))""",
                )
            }
        }
    }

    @AfterTest
    fun tearDown() {
        db?.close()
        container?.stop()
    }

    @Test
    fun cancelledSuspendTransactionRollsBackAndReleases() {
        if (!dockerAvailable) return
        val database = db!!
        runBlocking {
            val id = Uuid.random()
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(300) {
                    database.suspendTransaction {
                        Widgets.insert(Widget().apply { this.id = id; name = "doomed"; qty = 1 })
                        delay(10_000) // cancelled while the transaction is open
                    }
                }
            }
            // Rolled back, and the single pooled connection is back (else this query would block).
            val found = withTimeout(5_000) { database.suspendAutocommit { Widgets.findOne { where { Widgets.id eq id } } } }
            assertNull(found)
        }
    }

    @Test
    fun cancelledSuspendAutocommitReleasesConnection() {
        if (!dockerAvailable) return
        val database = db!!
        runBlocking {
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(300) {
                    database.suspendAutocommit {
                        Widgets.find(Query(Widgets.id eq Uuid.random()))
                        delay(10_000) // cancelled while the connection is pinned
                    }
                }
            }
            // Connection released: a normal query completes.
            withTimeout(5_000) { database.suspendAutocommit { Widgets.findOne { where { Widgets.id eq Uuid.random() } } } }
        }
    }
}
