@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Table
import io.github.kormium.connectNotifications
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.r2dbc.createR2dbcDatabase
import io.github.kormium.r2dbc.r2dbcListenNotifyTransport
import io.github.kormium.suspendAutocommit
import io.github.kormium.suspendTransaction
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.Test
import kotlin.test.assertEquals

object R2NotifyCatalog : Catalog

class R2NotifyRow : Entity() {
    var id by R2NotifyThings.id
}

object R2NotifyThings : Table<R2NotifyCatalog, R2NotifyRow>("r2_notify_things", ::R2NotifyRow) {
    val id by Column.Int().primaryKey()

    init { id }
}

/** Same cross-instance proof as the JDBC test, but over the async r2dbc transport. */
class R2dbcNotificationIntegrationTest {

    @Test
    fun commitOnOneInstanceNotifiesAnother() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is not available")
        val container = PostgreSQLContainer("postgres:18-alpine").apply { start() }
        try {
            fun connect() = createR2dbcDatabase(
                host = container.host,
                port = container.firstMappedPort,
                database = container.databaseName,
                user = container.username,
                password = container.password,
            )

            val driverA = connect()
            val driverB = connect()
            val sdbA: SuspendDatabase<R2NotifyCatalog> = driverA
            try {
                runBlocking {
                    sdbA.suspendAutocommit {
                        R2NotifyThings.execSql("""CREATE TABLE IF NOT EXISTS "r2_notify_things" ("id" integer PRIMARY KEY)""")
                    }

                    val fired = Channel<Set<String>>(Channel.UNLIMITED)
                    driverB.writeListeners.add { fired.trySend(it) }

                    val regB = driverB.connectNotifications(
                        r2dbcListenNotifyTransport(
                            container.host, container.firstMappedPort,
                            container.databaseName, container.username, container.password,
                        ),
                    )
                    val regA = driverA.connectNotifications(
                        r2dbcListenNotifyTransport(
                            container.host, container.firstMappedPort,
                            container.databaseName, container.username, container.password,
                        ),
                    )
                    try {
                        delay(2_000) // let driver B's LISTEN connection establish

                        sdbA.suspendTransaction { R2NotifyThings.insert(R2NotifyRow().apply { id = 1 }) }

                        val got = withTimeout(15_000) { fired.receive() }
                        assertEquals(setOf("r2_notify_things"), got)
                    } finally {
                        regA.remove()
                        regB.remove()
                    }
                }
            } finally {
                driverA.close()
                driverB.close()
            }
        } finally {
            container.stop()
        }
    }
}
